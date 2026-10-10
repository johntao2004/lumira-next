-- Server-side, per-session role-preview state. The Spring Session primary key
-- keeps contexts shared across application instances and removes them at logout.
CREATE TABLE platform_role_preview_context (
    session_primary_id character(36) PRIMARY KEY
        REFERENCES spring_session(primary_id) ON DELETE CASCADE,
    actor_id uuid NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
    revision uuid NOT NULL,
    state varchar(8) NOT NULL DEFAULT 'NONE'
        CHECK (state IN ('NONE', 'ACTIVE', 'BLOCKED')),
    role_id uuid,
    role_code varchar(100),
    role_name varchar(200),
    role_version bigint,
    data_scope varchar(20),
    started_at timestamptz,
    expires_at timestamptz,
    limited_by_actor boolean NOT NULL DEFAULT false,
    blocked_reason varchar(40),
    actor_authority_digest char(64),
    role_authority_digest char(64),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((state = 'NONE') = (role_id IS NULL)),
    CHECK ((state = 'NONE') = (expires_at IS NULL)),
    CHECK ((state = 'NONE') = (actor_authority_digest IS NULL)),
    CHECK ((state = 'NONE') = (role_authority_digest IS NULL))
);

CREATE INDEX platform_role_preview_expiry_idx
    ON platform_role_preview_context (expires_at, session_primary_id)
    WHERE state = 'ACTIVE';

CREATE TABLE platform_role_preview_resource_ticket (
    ticket_id uuid PRIMARY KEY,
    session_primary_id character(36) NOT NULL
        REFERENCES spring_session(primary_id) ON DELETE CASCADE,
    actor_id uuid NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
    context_revision uuid NOT NULL,
    resource_type varchar(32) NOT NULL CHECK (resource_type = 'FILE_PREVIEW'),
    resource_id uuid NOT NULL,
    page_number integer NOT NULL CHECK (page_number BETWEEN 1 AND 500),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX platform_role_preview_ticket_expiry_idx
    ON platform_role_preview_resource_ticket (expires_at, ticket_id);

-- Existing deployments may grant only the new preview privilege through a
-- tightly scoped, reasoned bootstrap operation. Keep the before/after state
-- independently auditable without changing the legacy audit table contract.
CREATE TABLE platform_role_preview_bootstrap_audit (
    event_id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES platform_user(id),
    target_user_id uuid NOT NULL REFERENCES platform_user(id),
    operation varchar(8) NOT NULL CHECK (operation IN ('GRANT', 'REVOKE')),
    reason varchar(500) NOT NULL CHECK (length(trim(reason)) BETWEEN 8 AND 500),
    before_granted boolean NOT NULL,
    after_granted boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX platform_role_preview_bootstrap_audit_target_idx
    ON platform_role_preview_bootstrap_audit (target_user_id, created_at DESC);

-- Role-preview scopes are an additional predicate over the real visibility
-- functions. Actor identity and memberships always come from the authenticated
-- account; only target_role_id is supplied by the verified server context.
CREATE FUNCTION platform_role_preview_visible_users(
    actor_name text,
    permission_name text,
    target_role_id uuid,
    target_role_version bigint
) RETURNS TABLE(user_id uuid)
LANGUAGE sql STABLE
AS $$
WITH RECURSIVE actor AS (
    SELECT id FROM platform_user WHERE username = actor_name AND enabled AND deleted_at IS NULL
), target AS (
    SELECT r.data_scope
      FROM platform_role r
      JOIN platform_role_authority a ON a.role_id = r.id AND a.authority = permission_name
     WHERE r.id = target_role_id AND r.enabled AND r.version = target_role_version
), own_orgs AS (
    SELECT o.id
      FROM platform_user_organization uo
      JOIN actor a ON a.id = uo.user_id
      JOIN platform_organization o ON o.id = uo.organization_id AND o.enabled
), descendants AS (
    SELECT id FROM own_orgs
    UNION
    SELECT o.id
      FROM platform_organization o
      JOIN descendants d ON o.parent_id = d.id
     WHERE o.enabled
), target_visible AS (
    SELECT u.id
      FROM platform_user u
      CROSS JOIN target t
     WHERE t.data_scope = 'ALL'
        OR u.id IN (SELECT id FROM actor)
        OR (t.data_scope = 'SELF' AND u.id IN (SELECT id FROM actor))
        OR (t.data_scope = 'ORGANIZATION' AND EXISTS (
              SELECT 1 FROM platform_user_organization member
               WHERE member.user_id = u.id AND member.organization_id IN (SELECT id FROM own_orgs)))
        OR (t.data_scope = 'SUBTREE' AND EXISTS (
              SELECT 1 FROM platform_user_organization member
               WHERE member.user_id = u.id AND member.organization_id IN (SELECT id FROM descendants)))
)
SELECT real.user_id
  FROM platform_visible_users(actor_name, permission_name) real
 WHERE target_role_id IS NULL
UNION
SELECT real.user_id
  FROM platform_visible_users(actor_name, permission_name) real
  JOIN target_visible target ON target.id = real.user_id
$$;

CREATE FUNCTION platform_role_preview_visible_organizations(
    actor_name text,
    permission_name text,
    target_role_id uuid,
    target_role_version bigint
) RETURNS TABLE(organization_id uuid)
LANGUAGE sql STABLE
AS $$
WITH RECURSIVE actor AS (
    SELECT id FROM platform_user WHERE username = actor_name AND enabled AND deleted_at IS NULL
), target AS (
    SELECT r.data_scope
      FROM platform_role r
      JOIN platform_role_authority a ON a.role_id = r.id AND a.authority = permission_name
     WHERE r.id = target_role_id AND r.enabled AND r.version = target_role_version
), own_orgs AS (
    SELECT o.id
      FROM platform_user_organization uo
      JOIN actor a ON a.id = uo.user_id
      JOIN platform_organization o ON o.id = uo.organization_id AND o.enabled
), descendants AS (
    SELECT id FROM own_orgs
    UNION
    SELECT o.id
      FROM platform_organization o
      JOIN descendants d ON o.parent_id = d.id
     WHERE o.enabled
), target_visible AS (
    SELECT o.id
      FROM platform_organization o
      CROSS JOIN target t
     WHERE o.enabled
       AND (t.data_scope = 'ALL'
         OR (t.data_scope IN ('SELF', 'ORGANIZATION') AND o.id IN (SELECT id FROM own_orgs))
         OR (t.data_scope = 'SUBTREE' AND o.id IN (SELECT id FROM descendants)))
)
SELECT real.organization_id
  FROM platform_visible_organizations(actor_name, permission_name) real
 WHERE target_role_id IS NULL
UNION
SELECT real.organization_id
  FROM platform_visible_organizations(actor_name, permission_name) real
  JOIN target_visible target ON target.id = real.organization_id
$$;
