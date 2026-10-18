-- Final-state initialization for mfa on the platform B52 host.
-- Existing completed installations validate published history without replaying this SQL.

CREATE TABLE plugin_mfa_authenticator (
    user_id uuid PRIMARY KEY REFERENCES platform_user(id) ON DELETE CASCADE,
    label varchar(128) NOT NULL,
    secret_envelope text NOT NULL,
    confirmed boolean NOT NULL DEFAULT false,
    expires_at timestamptz,
    last_step bigint NOT NULL DEFAULT -1,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    setup_challenge_id uuid,
    setup_session_hash varchar(64),
    setup_attempts smallint NOT NULL DEFAULT 0,
    CHECK (confirmed OR expires_at IS NOT NULL),
    CONSTRAINT plugin_mfa_setup_attempts_check CHECK (setup_attempts BETWEEN 0 AND 5)
);
CREATE TABLE plugin_mfa_recovery (
    user_id uuid NOT NULL REFERENCES plugin_mfa_authenticator(user_id) ON DELETE CASCADE,
    digest char(64) NOT NULL,
    PRIMARY KEY(user_id,digest)
);
CREATE TABLE plugin_mfa_reset_audit (
    id uuid PRIMARY KEY,
    actor varchar(100) NOT NULL,
    target_id uuid NOT NULL,
    reason varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX plugin_mfa_setup_challenge_unique
    ON plugin_mfa_authenticator(setup_challenge_id) WHERE setup_challenge_id IS NOT NULL;
CREATE INDEX plugin_mfa_setup_expiry
    ON plugin_mfa_authenticator(expires_at) WHERE confirmed=false;
CREATE TABLE plugin_mfa_replacement (
    user_id uuid PRIMARY KEY REFERENCES platform_user(id) ON DELETE CASCADE,
    label varchar(128) NOT NULL,
    secret_envelope text NOT NULL,
    challenge_id uuid NOT NULL UNIQUE,
    session_hash varchar(64) NOT NULL CHECK (session_hash ~ '^[a-f0-9]{64}$'),
    factor_credential_revision uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    attempts smallint NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    authentication_revision uuid NOT NULL,
    last_step bigint NOT NULL DEFAULT -1,
    CHECK (length(trim(label)) > 0)
);
CREATE INDEX plugin_mfa_replacement_expiry ON plugin_mfa_replacement(expires_at);

-- BEGIN HOST PERMISSION ADOPTION
-- Published V1/V6 seed the old read/manage/reset codes. Preserve read/manage as independent
-- page+read grants, and translate reset through V50's global-reset AND target-scope components.
DO $$
BEGIN
    IF to_regprocedure('public.platform_normalize_legacy_permission_projections()') IS NULL THEN
        RAISE EXCEPTION 'mfa permission finalization requires platform migration V51';
    END IF;
END
$$;

WITH permission_map(source, target) AS (
    VALUES
        ('platform:mfa:read', 'platform:mfa:management-access'),
        ('platform:mfa:read', 'platform:mfa:management-read'),
        ('platform:mfa:manage', 'platform:mfa:management-access'),
        ('platform:mfa:manage', 'platform:mfa:management-read')
)
INSERT INTO platform_role_authority(role_id, authority)
SELECT DISTINCT existing.role_id, mapping.target
  FROM platform_role_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;

WITH permission_map(source, target) AS (
    VALUES
        ('platform:mfa:read', 'platform:mfa:management-access'),
        ('platform:mfa:read', 'platform:mfa:management-read'),
        ('platform:mfa:manage', 'platform:mfa:management-access'),
        ('platform:mfa:manage', 'platform:mfa:management-read')
)
INSERT INTO platform_authority(user_id, authority)
SELECT DISTINCT existing.user_id, mapping.target
  FROM platform_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;

-- A legacy reset marker is global-only; leave it opaque until the host normalizer can require
-- both the global marker and the target-scope component before materializing management-reset.
INSERT INTO platform_role_authority(role_id, authority)
SELECT role_id, 'platform:__v50:mfa-reset-global'
  FROM platform_role_authority
 WHERE authority = 'platform:mfa:reset'
ON CONFLICT DO NOTHING;

INSERT INTO platform_authority(user_id, authority)
SELECT user_id, 'platform:__v50:mfa-reset-global'
  FROM platform_authority
 WHERE authority = 'platform:mfa:reset'
ON CONFLICT DO NOTHING;

-- Preserve direct users:update scope for users who had a legacy reset marker directly or through
-- an enabled ALL-scope role. Disabled accounts remain represented; only role grants themselves
-- must be enabled to contribute an effective global marker.
INSERT INTO platform_authority(user_id, authority)
SELECT DISTINCT update_grant.user_id, 'platform:__v50:mfa-user-update-scope'
  FROM platform_authority update_grant
 WHERE update_grant.authority = 'platform:users:update'
   AND (
       EXISTS (
           SELECT 1
             FROM platform_authority reset_grant
            WHERE reset_grant.user_id = update_grant.user_id
              AND reset_grant.authority = 'platform:mfa:reset'
       )
       OR EXISTS (
           SELECT 1
             FROM platform_user_role membership
             JOIN platform_role reset_role
               ON reset_role.id = membership.role_id
              AND reset_role.enabled
              AND reset_role.data_scope = 'ALL'
             JOIN platform_role_authority reset_grant
               ON reset_grant.role_id = reset_role.id
              AND reset_grant.authority = 'platform:mfa:reset'
            WHERE membership.user_id = update_grant.user_id
       )
   )
ON CONFLICT DO NOTHING;

-- Preserve role-scoped users:update grants at their original role and scope when an assigned
-- actor has any eligible legacy reset marker. This supports reset and target scope split across
-- direct grants and multiple roles. A disabled ALL role retains its latent local conjunction.
INSERT INTO platform_role_authority(role_id, authority)
SELECT DISTINCT update_grant.role_id, 'platform:__v50:mfa-user-update-scope'
  FROM platform_role_authority update_grant
  JOIN platform_role update_role
    ON update_role.id = update_grant.role_id
 WHERE update_grant.authority = 'platform:users:update'
   AND (
       (
           update_role.enabled
           AND EXISTS (
               SELECT 1
                 FROM platform_user_role update_membership
                WHERE update_membership.role_id = update_role.id
                  AND (
                      EXISTS (
                          SELECT 1
                            FROM platform_authority direct_reset
                           WHERE direct_reset.user_id = update_membership.user_id
                             AND direct_reset.authority = 'platform:mfa:reset'
                      )
                      OR EXISTS (
                          SELECT 1
                            FROM platform_user_role reset_membership
                            JOIN platform_role reset_role
                              ON reset_role.id = reset_membership.role_id
                             AND reset_role.enabled
                             AND reset_role.data_scope = 'ALL'
                            JOIN platform_role_authority reset_grant
                              ON reset_grant.role_id = reset_role.id
                             AND reset_grant.authority = 'platform:mfa:reset'
                           WHERE reset_membership.user_id = update_membership.user_id
                      )
                  )
           )
       )
       OR (
           update_role.data_scope = 'ALL'
           AND EXISTS (
               SELECT 1
                 FROM platform_role_authority same_role_reset
                WHERE same_role_reset.role_id = update_role.id
                  AND same_role_reset.authority = 'platform:mfa:reset'
           )
       )
   )
ON CONFLICT DO NOTHING;

-- These names were the historical administrative permissions. Keep their retired status in the
-- migration metadata so later replays are archived and removed rather than reactivated.
INSERT INTO platform_permission_retired_authority(authority)
VALUES
    ('platform:mfa:manage'),
    ('platform:mfa:read'),
    ('platform:mfa:reset')
ON CONFLICT DO NOTHING;

-- Fail the plugin migration rather than silently leaving V1/V6 legacy grants live on a host
-- that has not installed V51. The helper is idempotent and keeps its mapping metadata for audit.
SELECT public.platform_normalize_legacy_permission_projections();
