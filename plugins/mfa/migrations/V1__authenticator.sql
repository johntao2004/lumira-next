CREATE TABLE plugin_mfa_authenticator (
 user_id uuid PRIMARY KEY REFERENCES platform_user(id) ON DELETE CASCADE,
 label varchar(128) NOT NULL, secret_envelope text NOT NULL,
 confirmed boolean NOT NULL DEFAULT false, expires_at timestamptz,
 last_step bigint NOT NULL DEFAULT -1, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK(confirmed OR expires_at IS NOT NULL)
);
CREATE TABLE plugin_mfa_recovery (
 user_id uuid NOT NULL REFERENCES plugin_mfa_authenticator(user_id) ON DELETE CASCADE,
 digest char(64) NOT NULL, PRIMARY KEY(user_id,digest)
);
-- Expose the new administration capability to the bootstrap recovery administrator only.
INSERT INTO platform_authority(user_id,authority)
 SELECT u.id,'platform:mfa:manage' FROM platform_user u WHERE u.username='admin' AND u.deleted_at IS NULL
 AND EXISTS(SELECT 1 FROM platform_authority a WHERE a.user_id=u.id AND a.authority='platform:roles:write')
 ON CONFLICT DO NOTHING;

CREATE TABLE plugin_mfa_reset_audit (
 id uuid PRIMARY KEY, actor varchar(100) NOT NULL, target_id uuid NOT NULL,
 reason varchar(500) NOT NULL, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
