-- Keep the current authenticator and recovery codes live until replacement confirmation.
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
    CHECK (length(trim(label)) > 0)
);

CREATE INDEX plugin_mfa_replacement_expiry
    ON plugin_mfa_replacement(expires_at);
