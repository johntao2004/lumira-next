-- Authentication policy is host-owned. Missing role rows mean INHERIT, while the global row
-- preserves the password/passkey behavior already available before this policy layer.
CREATE TABLE platform_authentication_policy_lock (
    id smallint PRIMARY KEY CHECK (id = 1)
);
INSERT INTO platform_authentication_policy_lock(id) VALUES (1);

CREATE TABLE platform_global_authentication_policy (
    id smallint PRIMARY KEY CHECK (id = 1),
    version bigint NOT NULL CHECK (version >= 1),
    revision uuid NOT NULL DEFAULT gen_random_uuid(),
    enabled_methods text[] NOT NULL DEFAULT ARRAY['PASSWORD','PASSKEY']::text[],
    mfa_required boolean NOT NULL DEFAULT false,
    updated_by varchar(100) NOT NULL DEFAULT 'system',
    change_reason varchar(1000) NOT NULL DEFAULT 'Initial authentication policy.',
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_global_auth_methods_check CHECK (
        cardinality(enabled_methods) BETWEEN 1 AND 5
        AND enabled_methods <@ ARRAY['PASSWORD','EMAIL_OTP','SMS_OTP','PASSKEY','WECHAT']::text[]
    )
);
INSERT INTO platform_global_authentication_policy(id,version)
VALUES (1,1);

CREATE TABLE platform_role_authentication_policy (
    role_id uuid PRIMARY KEY REFERENCES platform_role(id) ON DELETE CASCADE,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    revision uuid NOT NULL DEFAULT gen_random_uuid(),
    login_mode varchar(16) NOT NULL DEFAULT 'INHERIT',
    methods text[] NOT NULL DEFAULT ARRAY[]::text[],
    mfa_mode varchar(16) NOT NULL DEFAULT 'INHERIT',
    captcha_mode varchar(16) NOT NULL DEFAULT 'INHERIT',
    updated_by varchar(100) NOT NULL,
    change_reason varchar(1000) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_role_auth_login_mode_check CHECK (login_mode IN ('INHERIT','RESTRICT')),
    CONSTRAINT platform_role_auth_mfa_mode_check CHECK (mfa_mode IN ('INHERIT','REQUIRED')),
    CONSTRAINT platform_role_auth_captcha_mode_check CHECK (captcha_mode IN ('INHERIT','ALWAYS')),
    CONSTRAINT platform_role_auth_methods_check CHECK (
        methods <@ ARRAY['PASSWORD','EMAIL_OTP','SMS_OTP','PASSKEY','WECHAT']::text[]
        AND (login_mode <> 'RESTRICT' OR cardinality(methods) BETWEEN 1 AND 5)
    )
);

-- Existing V23 requirements represented the user's explicit enrollment, so migrate them to
-- SELF_ENABLED. Policy-derived requirements are evaluated from global and assigned role policy.
ALTER TABLE platform_second_factor_requirement
    ADD COLUMN self_enabled boolean NOT NULL DEFAULT true,
    ADD COLUMN recovery_pending boolean NOT NULL DEFAULT false;

ALTER TABLE platform_login_challenge
    ALTER COLUMN requirement_version DROP NOT NULL,
    ADD COLUMN primary_method varchar(16) NOT NULL DEFAULT 'PASSWORD',
    ADD COLUMN state varchar(32) NOT NULL DEFAULT 'MFA_REQUIRED',
    ADD COLUMN policy_revision uuid NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT platform_login_challenge_method_check CHECK (
        primary_method IN ('PASSWORD','EMAIL_OTP','SMS_OTP','PASSKEY','WECHAT')
    ),
    ADD CONSTRAINT platform_login_challenge_state_check CHECK (
        state IN ('MFA_REQUIRED','ENROLLMENT_REQUIRED','RECOVERY_REQUIRED')
    );

CREATE INDEX platform_role_auth_policy_revision ON platform_role_authentication_policy(revision);
CREATE INDEX platform_second_factor_requirement_sources
    ON platform_second_factor_requirement(user_id)
    WHERE self_enabled OR recovery_pending;
