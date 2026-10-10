-- A pending replacement tracks its last accepted TOTP step independently from
-- the active authenticator so the same code cannot be replayed at confirmation.
ALTER TABLE plugin_mfa_replacement
    ADD COLUMN last_step bigint NOT NULL DEFAULT -1;
