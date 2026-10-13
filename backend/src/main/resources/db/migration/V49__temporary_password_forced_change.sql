-- A forced change is tied to the credential epoch; the temporary secret itself lives only as the
-- user's normal password hash. Claiming it consumes the one-time login proof.
CREATE TABLE platform_password_change_requirement (
    user_id uuid PRIMARY KEY REFERENCES platform_user(id) ON DELETE CASCADE,
    primary_credential_revision uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    claimed_at timestamptz,
    issued_by uuid NOT NULL REFERENCES platform_user(id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_password_change_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT platform_password_change_claim_check CHECK (claimed_at IS NULL OR claimed_at >= created_at)
);

ALTER TABLE platform_login_challenge
    DROP CONSTRAINT platform_login_challenge_state_check,
    ADD CONSTRAINT platform_login_challenge_state_check CHECK (
        state IN (
            'MFA_REQUIRED', 'ENROLLMENT_REQUIRED', 'RECOVERY_REQUIRED', 'CAPTCHA_REQUIRED',
            'PASSWORD_CHANGE_REQUIRED'
        )
    );

-- Keep existing global user administrators able to reset passwords; the guard still checks the
-- dedicated permission globally and applies its visible-user scope to the selected target.
INSERT INTO platform_role_authority(role_id, authority)
SELECT DISTINCT grants.role_id, 'platform:users:reset-password'
FROM platform_role_authority grants
JOIN platform_role role ON role.id=grants.role_id
WHERE grants.authority='platform:users:update' AND role.enabled AND role.data_scope='ALL'
ON CONFLICT DO NOTHING;

INSERT INTO platform_authority(user_id, authority)
SELECT DISTINCT user_id, 'platform:users:reset-password'
FROM platform_authority
WHERE authority='platform:users:update'
ON CONFLICT DO NOTHING;
