ALTER TABLE platform_user
    ADD COLUMN deleted_at timestamp with time zone;

CREATE INDEX platform_user_deleted_at_idx
    ON platform_user (deleted_at);
