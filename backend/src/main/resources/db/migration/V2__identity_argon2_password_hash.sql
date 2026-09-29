-- owner=identity
-- Argon2id encodings include an explicit DelegatingPasswordEncoder id and
-- therefore need more room than the legacy raw BCrypt value.
ALTER TABLE public.platform_user
    ALTER COLUMN password_hash TYPE character varying(256);
