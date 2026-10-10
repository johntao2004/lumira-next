-- Requirements belong to identity, independently of any installed provider.
CREATE TABLE platform_second_factor_requirement (
 user_id uuid PRIMARY KEY REFERENCES platform_user(id) ON DELETE CASCADE,
 provider_id varchar(64) NOT NULL CHECK(provider_id ~ '^[a-z][a-z0-9-]{0,63}$'),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE platform_login_challenge (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
 provider_id varchar(64) NOT NULL, session_hash char(64) NOT NULL,
 expires_at timestamptz NOT NULL, attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5)
);
CREATE INDEX platform_login_challenge_expiry ON platform_login_challenge(expires_at);
