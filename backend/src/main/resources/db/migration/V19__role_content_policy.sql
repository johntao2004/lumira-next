CREATE TABLE platform_sensitive_role_policy (
    role_id uuid PRIMARY KEY REFERENCES platform_role(id) ON DELETE CASCADE,
    mode varchar(16) NOT NULL CHECK (mode IN ('ON','OFF')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    updated_by varchar(100) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE platform_sensitive_role_policy IS 'Role control switch over shared word dictionary; missing row defaults ON';
CREATE TABLE platform_content_policy_hit (
    id uuid PRIMARY KEY,
    actor varchar(100) NOT NULL,
    source varchar(300) NOT NULL,
    action varchar(16) NOT NULL CHECK (action IN ('BLOCK','LOG_ONLY')),
    match_count integer NOT NULL CHECK (match_count > 0),
    role_ids uuid[] NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE platform_content_policy_hit IS 'Moderation metadata only; never stores submitted text or credentials';
CREATE INDEX platform_content_policy_hit_recent ON platform_content_policy_hit(created_at DESC,id);
