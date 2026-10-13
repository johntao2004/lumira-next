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
