ALTER TABLE public.platform_user
    ADD COLUMN file_quota_bytes_override bigint,
    ADD COLUMN file_quota_files_override bigint,
    ADD CONSTRAINT platform_user_file_quota_bytes_nonnegative
        CHECK (file_quota_bytes_override IS NULL OR file_quota_bytes_override >= 0),
    ADD CONSTRAINT platform_user_file_quota_files_nonnegative
        CHECK (file_quota_files_override IS NULL OR file_quota_files_override >= 0);
