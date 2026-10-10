CREATE TABLE platform_captcha_settings (
    id smallint PRIMARY KEY CHECK (id = 1),
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    revision uuid NOT NULL DEFAULT gen_random_uuid(),
    enabled boolean NOT NULL DEFAULT false,
    challenge_type varchar(16) NOT NULL DEFAULT 'IMAGE_TEXT'
        CHECK (challenge_type IN ('IMAGE_TEXT', 'SLIDER')),
    access_key_id varchar(128) NOT NULL DEFAULT '',
    primary_app_id varchar(20) NOT NULL DEFAULT '',
    accessible_app_id varchar(20) NOT NULL DEFAULT '',
    timeout_seconds smallint NOT NULL DEFAULT 5 CHECK (timeout_seconds BETWEEN 1 AND 5),
    actions jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_by varchar(100) NOT NULL DEFAULT 'system',
    change_reason varchar(1000) NOT NULL DEFAULT 'CAPTCHA policy initialized.',
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_captcha_actions_object CHECK (jsonb_typeof(actions) = 'object')
);

INSERT INTO platform_captcha_settings(id) VALUES (1);

ALTER TABLE platform_login_challenge
    DROP CONSTRAINT platform_login_challenge_state_check,
    ADD CONSTRAINT platform_login_challenge_state_check CHECK (
        state IN ('MFA_REQUIRED', 'ENROLLMENT_REQUIRED', 'RECOVERY_REQUIRED', 'CAPTCHA_REQUIRED')
    ),
    ADD COLUMN captcha_policy_revision varchar(128),
    ADD COLUMN captcha_satisfied boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT platform_login_challenge_captcha_revision_check
        CHECK (captcha_policy_revision IS NULL OR length(captcha_policy_revision) BETWEEN 1 AND 128);

CREATE TABLE platform_captcha_challenge (
    id uuid PRIMARY KEY,
    session_digest char(64) NOT NULL CHECK (session_digest ~ '^[a-f0-9]{64}$'),
    action varchar(40) NOT NULL CHECK (action IN (
        'PASSWORD_LOGIN', 'PASSKEY_LOGIN', 'WECHAT_LOGIN', 'LOGIN_CODE_SEND', 'LOGIN_CODE_VERIFY',
        'REGISTRATION_SEND', 'REGISTRATION_VERIFY', 'REGISTRATION_COMPLETE',
        'PASSWORD_RECOVERY_REQUEST', 'PASSWORD_RECOVERY_VERIFY', 'PASSWORD_RECOVERY_CONFIRM',
        'POST_PRIMARY_AUTH'
    )),
    attempt_id varchar(128),
    policy_revision varchar(128) NOT NULL CHECK (length(policy_revision) BETWEEN 1 AND 128),
    settings_version bigint NOT NULL CHECK (settings_version >= 1),
    provider_app_id varchar(20) NOT NULL,
    accessible_alternative boolean NOT NULL DEFAULT false,
    challenge_type varchar(16) NOT NULL CHECK (challenge_type IN ('IMAGE_TEXT', 'SLIDER', 'AUDIO')),
    state varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'VERIFYING', 'VERIFIED', 'LOCKED', 'EXPIRED')),
    claim_id uuid,
    claimed_at timestamptz,
    attempts smallint NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz NOT NULL,
    CONSTRAINT platform_captcha_attempt_id_check CHECK (attempt_id IS NULL OR length(attempt_id) BETWEEN 1 AND 128),
    CONSTRAINT platform_captcha_claim_state_check CHECK ((state = 'VERIFYING') = (claim_id IS NOT NULL))
);

CREATE INDEX platform_captcha_challenge_session_idx
    ON platform_captcha_challenge(session_digest, action, created_at DESC);
CREATE INDEX platform_captcha_challenge_expiry_idx
    ON platform_captcha_challenge(expires_at)
    WHERE state IN ('PENDING', 'VERIFYING', 'VERIFIED');
CREATE UNIQUE INDEX platform_captcha_one_active_challenge_per_action
    ON platform_captcha_challenge(session_digest, action)
    WHERE state IN ('PENDING', 'VERIFYING', 'VERIFIED');

CREATE TABLE platform_captcha_proof (
    id uuid PRIMARY KEY,
    challenge_id uuid NOT NULL UNIQUE REFERENCES platform_captcha_challenge(id) ON DELETE CASCADE,
    session_digest char(64) NOT NULL CHECK (session_digest ~ '^[a-f0-9]{64}$'),
    action varchar(40) NOT NULL CHECK (action IN (
        'PASSWORD_LOGIN', 'PASSKEY_LOGIN', 'WECHAT_LOGIN', 'LOGIN_CODE_SEND', 'LOGIN_CODE_VERIFY',
        'REGISTRATION_SEND', 'REGISTRATION_VERIFY', 'REGISTRATION_COMPLETE',
        'PASSWORD_RECOVERY_REQUEST', 'PASSWORD_RECOVERY_VERIFY', 'PASSWORD_RECOVERY_CONFIRM',
        'POST_PRIMARY_AUTH'
    )),
    attempt_id varchar(128),
    policy_revision varchar(128) NOT NULL CHECK (length(policy_revision) BETWEEN 1 AND 128),
    issued_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    CONSTRAINT platform_captcha_proof_attempt_id_check CHECK (attempt_id IS NULL OR length(attempt_id) BETWEEN 1 AND 128)
);

CREATE INDEX platform_captcha_proof_expiry_idx ON platform_captcha_proof(expires_at)
    WHERE consumed_at IS NULL;

ALTER TABLE platform_passkey_challenge
    ADD COLUMN captcha_policy_revision varchar(128),
    ADD COLUMN captcha_satisfied boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT platform_passkey_captcha_revision_check
        CHECK (captcha_policy_revision IS NULL OR length(captcha_policy_revision) BETWEEN 1 AND 128);
