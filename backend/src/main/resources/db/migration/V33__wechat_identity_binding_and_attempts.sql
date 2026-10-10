CREATE TABLE platform_wechat_binding (
    user_id uuid PRIMARY KEY REFERENCES platform_user(id) ON DELETE CASCADE,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    app_id varchar(64),
    open_id_digest char(64),
    nickname varchar(256),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_wechat_binding_identity_check CHECK (
        (app_id IS NULL AND open_id_digest IS NULL AND nickname IS NULL)
        OR (app_id IS NOT NULL AND length(app_id) BETWEEN 1 AND 64
            AND open_id_digest ~ '^[a-f0-9]{64}$' AND version >= 1)
    )
);

CREATE UNIQUE INDEX platform_wechat_binding_identity_key
    ON platform_wechat_binding(app_id, open_id_digest)
    WHERE open_id_digest IS NOT NULL;

CREATE TABLE platform_wechat_attempt (
    id uuid PRIMARY KEY,
    purpose varchar(16) NOT NULL CHECK (purpose IN ('LOGIN', 'BIND', 'REGISTRATION')),
    session_digest char(64) NOT NULL CHECK (session_digest ~ '^[a-f0-9]{64}$'),
    state_digest char(64) NOT NULL UNIQUE CHECK (state_digest ~ '^[a-f0-9]{64}$'),
    attempt_state varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (attempt_state IN ('PENDING', 'CLAIMED', 'AUTHENTICATING', 'READY', 'UNBOUND', 'FAILED', 'EXPIRED', 'CANCELLED', 'CONSUMED', 'SUPERSEDED')),
    settings_version bigint NOT NULL CHECK (settings_version >= 0),
    app_id varchar(64) NOT NULL CHECK (length(app_id) BETWEEN 1 AND 64),
    contact_proof_id uuid REFERENCES platform_public_auth_challenge(proof_id) ON DELETE CASCADE,
    user_id uuid REFERENCES platform_user(id) ON DELETE CASCADE,
    base_binding_version bigint,
    replace_binding boolean NOT NULL DEFAULT false,
    authentication_revision uuid,
    primary_credential_revision uuid,
    factor_credential_revision uuid,
    open_id_digest char(64) CHECK (open_id_digest IS NULL OR open_id_digest ~ '^[a-f0-9]{64}$'),
    nickname varchar(256),
    registration_proof_id uuid UNIQUE,
    login_response jsonb CHECK (login_response IS NULL OR jsonb_typeof(login_response) = 'object'),
    failure_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz NOT NULL,
    claimed_at timestamptz,
    completed_at timestamptz,
    CONSTRAINT platform_wechat_attempt_registration_check CHECK (
        (purpose = 'REGISTRATION' AND contact_proof_id IS NOT NULL AND user_id IS NULL)
        OR (purpose <> 'REGISTRATION' AND contact_proof_id IS NULL)
    ),
    CONSTRAINT platform_wechat_attempt_binding_check CHECK (
        (purpose = 'BIND' AND user_id IS NOT NULL AND base_binding_version IS NOT NULL
            AND authentication_revision IS NOT NULL AND primary_credential_revision IS NOT NULL
            AND factor_credential_revision IS NOT NULL)
        OR (purpose <> 'BIND' AND user_id IS NULL AND base_binding_version IS NULL
            AND authentication_revision IS NULL AND primary_credential_revision IS NULL
            AND factor_credential_revision IS NULL)
    ),
    CONSTRAINT platform_wechat_attempt_result_check CHECK (
        attempt_state <> 'READY' OR open_id_digest IS NOT NULL
    ),
    CONSTRAINT platform_wechat_attempt_proof_check CHECK (
        registration_proof_id IS NULL OR (purpose = 'REGISTRATION' AND attempt_state = 'READY')
    ),
    CONSTRAINT platform_wechat_attempt_login_check CHECK (
        login_response IS NULL OR (purpose = 'LOGIN' AND attempt_state = 'READY')
    ),
    CONSTRAINT platform_wechat_attempt_failure_check CHECK (
        failure_code IS NULL OR attempt_state = 'FAILED'
    )
);

CREATE INDEX platform_wechat_attempt_session_idx
    ON platform_wechat_attempt(session_digest, created_at DESC, id DESC);
CREATE INDEX platform_wechat_attempt_expiry_idx
    ON platform_wechat_attempt(expires_at)
    WHERE attempt_state IN ('PENDING', 'CLAIMED', 'READY');
