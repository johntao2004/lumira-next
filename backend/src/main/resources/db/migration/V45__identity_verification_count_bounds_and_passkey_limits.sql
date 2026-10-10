-- Keep persisted challenge counters aligned with SecuritySettings.validate().
ALTER TABLE platform_account_email
    DROP CONSTRAINT platform_account_email_failures_check,
    DROP CONSTRAINT platform_account_email_request_count_check,
    ADD CONSTRAINT platform_account_email_failures_check CHECK (failures BETWEEN 0 AND 20),
    ADD CONSTRAINT platform_account_email_request_count_check CHECK (request_count BETWEEN 0 AND 10000);

ALTER TABLE platform_account_phone
    DROP CONSTRAINT platform_account_phone_failures_check,
    DROP CONSTRAINT platform_account_phone_request_count_check,
    ADD CONSTRAINT platform_account_phone_failures_check CHECK (failures BETWEEN 0 AND 20),
    ADD CONSTRAINT platform_account_phone_request_count_check CHECK (request_count BETWEEN 0 AND 10000);

ALTER TABLE platform_password_reset
    DROP CONSTRAINT platform_password_reset_failures_check,
    DROP CONSTRAINT platform_password_reset_request_count_check,
    ADD CONSTRAINT platform_password_reset_failures_check CHECK (failures BETWEEN 0 AND 20),
    ADD CONSTRAINT platform_password_reset_request_count_check CHECK (request_count BETWEEN 1 AND 10000);

CREATE INDEX platform_passkey_challenge_session_kind
    ON platform_passkey_challenge (session_id, kind, expires_at, id);
CREATE INDEX platform_passkey_challenge_cleanup
    ON platform_passkey_challenge (expires_at, id);
