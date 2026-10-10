CREATE TABLE platform_registration_policy (
    id smallint PRIMARY KEY CHECK (id = 1),
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    enabled boolean NOT NULL DEFAULT false,
    channels text[] NOT NULL DEFAULT ARRAY[]::text[],
    default_role_id uuid,
    updated_by varchar(100) NOT NULL DEFAULT 'system',
    change_reason varchar(1000) NOT NULL DEFAULT 'System initialized registration policy.',
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_registration_policy_channels_check
        CHECK (channels <@ ARRAY['EMAIL', 'SMS']::text[] AND cardinality(channels) <= 2
            AND (cardinality(channels) < 2 OR channels[1] <> channels[2])),
    CONSTRAINT platform_registration_policy_enabled_check
        CHECK (NOT enabled OR (cardinality(channels) > 0 AND default_role_id IS NOT NULL)),
    CONSTRAINT platform_registration_policy_default_role_fkey
        FOREIGN KEY (default_role_id) REFERENCES platform_role(id) ON DELETE RESTRICT
);

INSERT INTO platform_registration_policy(id) VALUES (1);

CREATE TABLE platform_agreement_snapshot (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    version bigint NOT NULL CHECK (version >= 1),
    user_agreement_markdown text NOT NULL,
    privacy_agreement_markdown text NOT NULL,
    content_hash char(64) NOT NULL CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_agreement_snapshot_user_size_check
        CHECK (octet_length(user_agreement_markdown) <= 100000),
    CONSTRAINT platform_agreement_snapshot_privacy_size_check
        CHECK (octet_length(privacy_agreement_markdown) <= 100000),
    CONSTRAINT platform_agreement_snapshot_version_key UNIQUE (version)
);

CREATE TABLE platform_account_agreement_acceptance (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
    snapshot_id uuid NOT NULL REFERENCES platform_agreement_snapshot(id) ON DELETE RESTRICT,
    request_id uuid NOT NULL UNIQUE,
    accepted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_account_agreement_acceptance_user_snapshot_key UNIQUE (user_id, snapshot_id)
);

CREATE TABLE platform_public_auth_challenge (
    id uuid PRIMARY KEY,
    purpose varchar(24) NOT NULL CHECK (purpose IN ('REGISTRATION', 'LOGIN', 'SMS_RECOVERY')),
    channel varchar(8) NOT NULL CHECK (channel IN ('EMAIL', 'SMS')),
    session_digest char(64) NOT NULL CHECK (session_digest ~ '^[a-f0-9]{64}$'),
    contact_digest char(64) NOT NULL CHECK (contact_digest ~ '^[a-f0-9]{64}$'),
    contact_ciphertext text NOT NULL,
    code_digest char(64) CHECK (code_digest IS NULL OR code_digest ~ '^[a-f0-9]{64}$'),
    user_id uuid REFERENCES platform_user(id) ON DELETE CASCADE,
    contact_version bigint,
    primary_credential_revision uuid,
    state varchar(16) NOT NULL CHECK (state IN ('PENDING', 'VERIFIED', 'CONSUMED', 'EXPIRED', 'LOCKED', 'SUPERSEDED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 20),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resend_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    proof_id uuid UNIQUE,
    proof_expires_at timestamptz,
    consumed_at timestamptz,
    CONSTRAINT platform_public_auth_challenge_proof_check
        CHECK ((proof_id IS NULL) = (proof_expires_at IS NULL))
);

CREATE INDEX platform_public_auth_challenge_session_idx
    ON platform_public_auth_challenge(session_digest, purpose, created_at DESC);

CREATE INDEX platform_public_auth_challenge_expiry_idx
    ON platform_public_auth_challenge(expires_at)
    WHERE state IN ('PENDING', 'VERIFIED');

CREATE TABLE platform_registration_completion (
    request_id uuid PRIMARY KEY,
    session_digest char(64) NOT NULL CHECK (session_digest ~ '^[a-f0-9]{64}$'),
    challenge_id uuid NOT NULL REFERENCES platform_public_auth_challenge(id) ON DELETE CASCADE,
    proof_id uuid NOT NULL,
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    account_id uuid REFERENCES platform_user(id) ON DELETE SET NULL,
    receipt jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamptz,
    CONSTRAINT platform_registration_completion_proof_fkey
        FOREIGN KEY (proof_id) REFERENCES platform_public_auth_challenge(proof_id) ON DELETE CASCADE,
    CONSTRAINT platform_registration_completion_state_check
        CHECK ((account_id IS NULL AND receipt IS NULL AND completed_at IS NULL)
            OR (account_id IS NOT NULL AND receipt IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE INDEX platform_registration_completion_cleanup_idx
    ON platform_registration_completion(created_at);

CREATE TABLE platform_public_auth_rate (
    purpose varchar(24) NOT NULL CHECK (purpose IN ('REGISTRATION', 'LOGIN', 'SMS_RECOVERY')),
    channel varchar(8) NOT NULL CHECK (channel IN ('EMAIL', 'SMS')),
    rate_digest char(64) NOT NULL CHECK (rate_digest ~ '^[a-f0-9]{64}$'),
    requested_at timestamptz,
    request_day date,
    request_count integer NOT NULL DEFAULT 0 CHECK (request_count BETWEEN 0 AND 10000),
    failures integer NOT NULL DEFAULT 0 CHECK (failures BETWEEN 0 AND 20),
    PRIMARY KEY (purpose, channel, rate_digest)
);

CREATE INDEX platform_public_auth_rate_cleanup_idx
    ON platform_public_auth_rate(request_day);
