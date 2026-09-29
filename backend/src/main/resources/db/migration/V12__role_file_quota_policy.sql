ALTER TABLE public.platform_role
    ADD COLUMN file_quota_bytes_limit bigint,
    ADD COLUMN file_quota_files_limit bigint,
    ADD CONSTRAINT platform_role_file_quota_bytes_nonnegative
        CHECK (file_quota_bytes_limit IS NULL OR file_quota_bytes_limit >= 0),
    ADD CONSTRAINT platform_role_file_quota_files_nonnegative
        CHECK (file_quota_files_limit IS NULL OR file_quota_files_limit >= 0);

-- V11 platform_user quota columns and their existing values remain intact as
-- a recovery source. Runtime enforcement moves to role policy in application code.
