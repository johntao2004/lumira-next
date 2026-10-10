ALTER TABLE plugin_mfa_authenticator
    ADD COLUMN setup_challenge_id uuid,
    ADD COLUMN setup_session_hash varchar(64),
    ADD COLUMN setup_attempts smallint NOT NULL DEFAULT 0;

ALTER TABLE plugin_mfa_authenticator
    ADD CONSTRAINT plugin_mfa_setup_attempts_check CHECK (setup_attempts BETWEEN 0 AND 5);

CREATE UNIQUE INDEX plugin_mfa_setup_challenge_unique
    ON plugin_mfa_authenticator(setup_challenge_id)
    WHERE setup_challenge_id IS NOT NULL;

CREATE INDEX plugin_mfa_setup_expiry
    ON plugin_mfa_authenticator(expires_at)
    WHERE confirmed=false;
