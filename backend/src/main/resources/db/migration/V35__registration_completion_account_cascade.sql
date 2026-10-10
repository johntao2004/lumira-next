ALTER TABLE platform_registration_completion
    DROP CONSTRAINT platform_registration_completion_account_id_fkey;

ALTER TABLE platform_registration_completion
    ADD CONSTRAINT platform_registration_completion_account_id_fkey
        FOREIGN KEY (account_id) REFERENCES platform_user(id) ON DELETE CASCADE;
