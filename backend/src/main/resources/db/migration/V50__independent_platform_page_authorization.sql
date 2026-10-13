-- Split the historically shared host permission keys into stable page-owned grants.
-- The mapping table is retained as audit metadata; old keys are removed from grants below.
CREATE TABLE platform_permission_legacy_mapping (
    old_authority varchar(120) NOT NULL,
    new_authority varchar(120) NOT NULL,
    PRIMARY KEY (old_authority, new_authority)
);

INSERT INTO platform_permission_legacy_mapping(old_authority, new_authority) VALUES
    ('platform:users:read', 'platform:users:access'),
    ('platform:users:read', 'platform:users:export'),
    ('platform:users:read', 'platform:online-users:access'),
    ('platform:users:write', 'platform:users:create'),
    ('platform:users:write', 'platform:users:update'),
    ('platform:users:write', 'platform:users:delete'),
    ('platform:users:read', 'platform:ai-console:read-users'),
    ('platform:users:reset-password', 'platform:__v50:users-reset-password'),
    ('platform:users:write', 'platform:online-users:terminate-session'),
    ('platform:users:terminate-session', 'platform:online-users:terminate-session'),
    ('platform:roles:read', 'platform:roles:access'),
    ('platform:roles:read', 'platform:users:read-roles'),
    ('platform:roles:read', 'platform:registration:read-roles'),
    ('platform:roles:write', 'platform:roles:create'),
    ('platform:roles:write', 'platform:roles:update'),
    ('platform:roles:write', 'platform:roles:delete'),
    ('platform:roles:assign', 'platform:__v50:users-assign-roles'),
    ('platform:organizations:read', 'platform:organizations:access'),
    ('platform:organizations:write', 'platform:organizations:create'),
    ('platform:organizations:write', 'platform:organizations:update'),
    ('platform:organizations:write', 'platform:organizations:delete'),
    ('platform:organizations:assign', 'platform:__v50:users-assign-organizations'),
    ('platform:audit:read', 'platform:audit:access'),
    ('platform:audit:read', 'platform:mail-deliveries:access'),
    ('platform:audit:read', 'platform:sms-deliveries:access'),
    ('platform:audit:read', 'platform:event-outbox:access'),
    ('platform:audit:read', 'platform:monitoring:access'),
    ('platform:audit:read', 'platform:api-docs:access'),
    ('platform:dictionaries:read', 'platform:dictionaries:access'),
    ('platform:dictionaries:read', 'platform:dictionaries:export'),
    ('platform:dictionaries:read', 'platform:profile-fields:read-dictionaries'),
    ('platform:dictionaries:write', 'platform:dictionaries:create'),
    ('platform:dictionaries:write', 'platform:dictionaries:update'),
    ('platform:dictionaries:write', 'platform:dictionaries:delete'),
    ('platform:dictionaries:write', 'platform:dictionaries:import'),
    ('platform:settings:read', 'platform:branding:access'),
    ('platform:settings:read', 'platform:smtp:access'),
    ('platform:settings:read', 'platform:sms:access'),
    ('platform:settings:read', 'platform:profile-fields:access'),
    ('platform:settings:read', 'platform:agreements:access'),
    ('platform:settings:read', 'platform:watermark:access'),
    ('platform:settings:read', 'platform:maintenance:access'),
    ('platform:settings:read', 'platform:security:access'),
    ('platform:settings:read', 'platform:authentication:access'),
    ('platform:settings:read', 'platform:passkey:access'),
    ('platform:settings:read', 'platform:wechat:access'),
    ('platform:settings:read', 'platform:captcha:access'),
    ('platform:settings:read', 'platform:registration:access'),
    ('platform:settings:read', 'platform:file-retention:access'),
    ('platform:settings:read', 'platform:ai-console:access'),
    ('platform:settings:read', 'platform:ai-models:access'),
    ('platform:settings:read', 'platform:registration:read-roles'),
    ('platform:settings:write', 'platform:branding:update'),
    ('platform:settings:write', 'platform:smtp:update'),
    ('platform:settings:write', 'platform:smtp:test'),
    ('platform:settings:write', 'platform:sms:update'),
    ('platform:settings:write', 'platform:sms:test'),
    ('platform:settings:write', 'platform:profile-fields:update'),
    ('platform:settings:write', 'platform:agreements:update'),
    ('platform:settings:write', 'platform:watermark:update'),
    ('platform:settings:write', 'platform:maintenance:update'),
    ('platform:settings:write', 'platform:security:update'),
    ('platform:settings:write', 'platform:authentication:update'),
    ('platform:settings:write', 'platform:passkey:update'),
    ('platform:settings:write', 'platform:wechat:update'),
    ('platform:settings:write', 'platform:captcha:update'),
    ('platform:settings:write', 'platform:registration:update'),
    ('platform:settings:write', 'platform:file-retention:update'),
    ('platform:settings:write', 'platform:ai-models:create'),
    ('platform:settings:write', 'platform:ai-models:update'),
    ('platform:settings:write', 'platform:ai-models:delete'),
    ('platform:settings:write', 'platform:ai-models:test'),
    ('platform:settings:create', 'platform:branding:update'),
    ('platform:settings:create', 'platform:smtp:update'),
    ('platform:settings:create', 'platform:smtp:test'),
    ('platform:settings:create', 'platform:sms:update'),
    ('platform:settings:create', 'platform:sms:test'),
    ('platform:settings:create', 'platform:profile-fields:update'),
    ('platform:settings:create', 'platform:agreements:update'),
    ('platform:settings:create', 'platform:watermark:update'),
    ('platform:settings:create', 'platform:maintenance:update'),
    ('platform:settings:create', 'platform:security:update'),
    ('platform:settings:create', 'platform:authentication:update'),
    ('platform:settings:create', 'platform:passkey:update'),
    ('platform:settings:create', 'platform:wechat:update'),
    ('platform:settings:create', 'platform:captcha:update'),
    ('platform:settings:create', 'platform:registration:update'),
    ('platform:settings:create', 'platform:file-retention:update'),
    ('platform:settings:create', 'platform:ai-models:create'),
    ('platform:settings:create', 'platform:ai-models:update'),
    ('platform:settings:create', 'platform:ai-models:delete'),
    ('platform:settings:create', 'platform:ai-models:test'),
    ('platform:settings:update', 'platform:branding:update'),
    ('platform:settings:update', 'platform:smtp:update'),
    ('platform:settings:update', 'platform:smtp:test'),
    ('platform:settings:update', 'platform:sms:update'),
    ('platform:settings:update', 'platform:sms:test'),
    ('platform:settings:update', 'platform:profile-fields:update'),
    ('platform:settings:update', 'platform:agreements:update'),
    ('platform:settings:update', 'platform:watermark:update'),
    ('platform:settings:update', 'platform:maintenance:update'),
    ('platform:settings:update', 'platform:security:update'),
    ('platform:settings:update', 'platform:authentication:update'),
    ('platform:settings:update', 'platform:passkey:update'),
    ('platform:settings:update', 'platform:wechat:update'),
    ('platform:settings:update', 'platform:captcha:update'),
    ('platform:settings:update', 'platform:registration:update'),
    ('platform:settings:update', 'platform:file-retention:update'),
    ('platform:settings:update', 'platform:ai-models:create'),
    ('platform:settings:update', 'platform:ai-models:update'),
    ('platform:settings:update', 'platform:ai-models:delete'),
    ('platform:settings:update', 'platform:ai-models:test'),
    ('platform:settings:delete', 'platform:branding:update'),
    ('platform:settings:delete', 'platform:smtp:update'),
    ('platform:settings:delete', 'platform:smtp:test'),
    ('platform:settings:delete', 'platform:sms:update'),
    ('platform:settings:delete', 'platform:sms:test'),
    ('platform:settings:delete', 'platform:profile-fields:update'),
    ('platform:settings:delete', 'platform:agreements:update'),
    ('platform:settings:delete', 'platform:watermark:update'),
    ('platform:settings:delete', 'platform:maintenance:update'),
    ('platform:settings:delete', 'platform:security:update'),
    ('platform:settings:delete', 'platform:authentication:update'),
    ('platform:settings:delete', 'platform:passkey:update'),
    ('platform:settings:delete', 'platform:wechat:update'),
    ('platform:settings:delete', 'platform:captcha:update'),
    ('platform:settings:delete', 'platform:registration:update'),
    ('platform:settings:delete', 'platform:file-retention:update'),
    ('platform:settings:delete', 'platform:ai-models:create'),
    ('platform:settings:delete', 'platform:ai-models:update'),
    ('platform:settings:delete', 'platform:ai-models:delete'),
    ('platform:settings:delete', 'platform:ai-models:test'),
    ('platform:modules:read', 'platform:modules:access'),
    ('platform:modules:write', 'platform:modules:install'),
    ('platform:modules:write', 'platform:modules:update'),
    ('platform:modules:write', 'platform:modules:uninstall'),
    ('platform:events:write', 'platform:event-outbox:replay'),
    ('platform:events:replay', 'platform:event-outbox:replay'),
    ('platform:navigation:read', 'platform:navigation:access'),
    ('platform:navigation:write', 'platform:navigation:create'),
    ('platform:navigation:write', 'platform:navigation:update'),
    ('platform:navigation:write', 'platform:navigation:delete'),
    ('platform:files:read', 'platform:files-all:access'),
    ('platform:files:read', 'platform:files-all:preview'),
    ('platform:files:read', 'platform:files-all:download'),
    ('platform:files:write', 'platform:files-all:delete'),
    ('platform:files:write', 'platform:files-all:restore'),
    ('platform:files:write', 'platform:files-all:purge'),
    ('platform:files:delete', 'platform:files-all:delete'),
    ('platform:files:delete', 'platform:files-all:purge'),
    ('platform:files:restore', 'platform:files-all:restore'),
    ('platform:notifications:read', 'platform:notifications:access'),
    ('platform:notifications:write', 'platform:notifications:create'),
    ('platform:mfa:reset', 'platform:__v50:mfa-reset-global'),
    ('platform:mfa:read', 'platform:mfa:management-access'),
    ('platform:mfa:read', 'platform:mfa:management-read'),
    ('platform:mfa:manage', 'platform:mfa:management-access'),
    ('platform:mfa:manage', 'platform:mfa:management-read');

-- Legacy compound capabilities are represented by operation-specific opaque source grants.
-- A logical operation is effective only when every listed source is present. Global-only source
-- requirements are kept separate from target-scope sources, so a scoped legacy grant cannot
-- become a global operation through delegation.
CREATE TABLE platform_permission_projection_component (
    logical_authority varchar(120) NOT NULL,
    source_authority varchar(120) NOT NULL UNIQUE,
    source_requires_global boolean NOT NULL DEFAULT false,
    target_scope_source boolean NOT NULL DEFAULT false,
    PRIMARY KEY (logical_authority, source_authority),
    CONSTRAINT platform_permission_projection_component_hidden_check
        CHECK (source_authority ~ '^platform:__v50:[a-z0-9:-]+$')
);

INSERT INTO platform_permission_projection_component(
    logical_authority, source_authority, source_requires_global, target_scope_source
) VALUES
    ('platform:users:assign-roles', 'platform:__v50:users-assign-roles', true, true),
    ('platform:users:assign-organizations', 'platform:__v50:users-assign-organizations', true, true),
    ('platform:users:reset-password', 'platform:__v50:users-reset-password', true, true),
    ('platform:ai-console:create-user', 'platform:__v50:ai-console-create-user-global', true, false),
    ('platform:ai-console:update-user', 'platform:__v50:ai-console-update-user-global', true, false),
    ('platform:ai-console:delete-user', 'platform:__v50:ai-console-delete-user-global', true, false),
    ('platform:roles:read-authentication-policy', 'platform:__v50:role-policy-read-roles', true, false),
    ('platform:roles:read-authentication-policy', 'platform:__v50:role-policy-read-settings', true, false),
    ('platform:roles:update-authentication-policy', 'platform:__v50:role-policy-update-roles', true, false),
    ('platform:roles:update-authentication-policy', 'platform:__v50:role-policy-update-settings', true, false),
    ('platform:registration:assign-default-role', 'platform:__v50:registration-assign-settings', true, false),
    ('platform:registration:assign-default-role', 'platform:__v50:registration-assign-roles', true, false),
    ('platform:mfa:management-reset', 'platform:__v50:mfa-reset-global', true, false),
    ('platform:mfa:management-reset', 'platform:__v50:mfa-user-update-scope', false, true);

-- Preserve each old component independently. This supports requirements split across a direct
-- grant and one or more roles without storing a broad synthesized grant.
INSERT INTO platform_role_authority(role_id, authority)
SELECT DISTINCT source.role_id, component.source_authority
  FROM platform_permission_projection_component component
  JOIN LATERAL (
      SELECT CASE component.source_authority
          WHEN 'platform:__v50:role-policy-read-roles' THEN 'platform:roles:read'
          WHEN 'platform:__v50:role-policy-read-settings' THEN 'platform:settings:read'
          WHEN 'platform:__v50:role-policy-update-roles' THEN 'platform:roles:update'
          WHEN 'platform:__v50:role-policy-update-settings' THEN 'platform:settings:update'
          WHEN 'platform:__v50:registration-assign-settings' THEN 'platform:settings:update'
          WHEN 'platform:__v50:registration-assign-roles' THEN 'platform:roles:assign'
          WHEN 'platform:__v50:mfa-reset-global' THEN 'platform:mfa:reset'
          WHEN 'platform:__v50:mfa-user-update-scope' THEN 'platform:users:update'
          WHEN 'platform:__v50:users-assign-roles' THEN 'platform:roles:assign'
          WHEN 'platform:__v50:users-assign-organizations' THEN 'platform:organizations:assign'
          WHEN 'platform:__v50:users-reset-password' THEN 'platform:users:reset-password'
          WHEN 'platform:__v50:ai-console-create-user-global' THEN 'platform:users:create'
          WHEN 'platform:__v50:ai-console-update-user-global' THEN 'platform:users:update'
          WHEN 'platform:__v50:ai-console-delete-user-global' THEN 'platform:users:delete'
      END AS old_authority
  ) old ON true
  JOIN platform_role_authority source ON source.authority = old.old_authority
ON CONFLICT DO NOTHING;

INSERT INTO platform_authority(user_id, authority)
SELECT DISTINCT source.user_id, component.source_authority
  FROM platform_permission_projection_component component
  JOIN LATERAL (
      SELECT CASE component.source_authority
          WHEN 'platform:__v50:role-policy-read-roles' THEN 'platform:roles:read'
          WHEN 'platform:__v50:role-policy-read-settings' THEN 'platform:settings:read'
          WHEN 'platform:__v50:role-policy-update-roles' THEN 'platform:roles:update'
          WHEN 'platform:__v50:role-policy-update-settings' THEN 'platform:settings:update'
          WHEN 'platform:__v50:registration-assign-settings' THEN 'platform:settings:update'
          WHEN 'platform:__v50:registration-assign-roles' THEN 'platform:roles:assign'
          WHEN 'platform:__v50:mfa-reset-global' THEN 'platform:mfa:reset'
          WHEN 'platform:__v50:mfa-user-update-scope' THEN 'platform:users:update'
          WHEN 'platform:__v50:users-assign-roles' THEN 'platform:roles:assign'
          WHEN 'platform:__v50:users-assign-organizations' THEN 'platform:organizations:assign'
          WHEN 'platform:__v50:users-reset-password' THEN 'platform:users:reset-password'
          WHEN 'platform:__v50:ai-console-create-user-global' THEN 'platform:users:create'
          WHEN 'platform:__v50:ai-console-update-user-global' THEN 'platform:users:update'
          WHEN 'platform:__v50:ai-console-delete-user-global' THEN 'platform:users:delete'
      END AS old_authority
  ) old ON true
  JOIN platform_authority source ON source.authority = old.old_authority
ON CONFLICT DO NOTHING;

INSERT INTO platform_role_authority(role_id, authority)
SELECT DISTINCT grants.role_id, mapping.new_authority
  FROM platform_role_authority grants
  JOIN platform_permission_legacy_mapping mapping ON mapping.old_authority = grants.authority
ON CONFLICT DO NOTHING;

INSERT INTO platform_authority(user_id, authority)
SELECT DISTINCT grants.user_id, mapping.new_authority
  FROM platform_authority grants
  JOIN platform_permission_legacy_mapping mapping ON mapping.old_authority = grants.authority
ON CONFLICT DO NOTHING;

DELETE FROM platform_role_authority grants
 USING platform_permission_legacy_mapping mapping
 WHERE mapping.old_authority = grants.authority;

DELETE FROM platform_authority grants
 USING platform_permission_legacy_mapping mapping
 WHERE mapping.old_authority = grants.authority;

-- Keep retired source codes inert while allowing a code that is also an active v2 operation to
-- be granted explicitly after migration. V50 has already copied its old rows to opaque sources.
CREATE TABLE platform_permission_retired_authority (
    authority varchar(120) PRIMARY KEY
);

INSERT INTO platform_permission_retired_authority(authority)
SELECT DISTINCT old_authority FROM platform_permission_legacy_mapping;

-- This code remains an active independent scoped operation. Old grants were copied to the
-- constrained hidden source above and deleted, so future explicit grants must remain visible.
DELETE FROM platform_permission_retired_authority
 WHERE authority = 'platform:users:reset-password';

-- Retain custom menu placement and presentation settings while switching each route to its own
-- page-access code. Self-service/account routes deliberately remain outside the grant catalog.
UPDATE platform_navigation_menu menu
   SET permission_key = route.access_permission,
       global_only = (route.access_scope = 'global')
  FROM (VALUES
      ('/dashboard/users', 'platform:users:access', 'scoped'),
      ('/dashboard/online-users', 'platform:online-users:access', 'global'),
      ('/dashboard/roles', 'platform:roles:access', 'global'),
      ('/dashboard/organizations', 'platform:organizations:access', 'scoped'),
      ('/dashboard/audit', 'platform:audit:access', 'global'),
      ('/dashboard/mail-deliveries', 'platform:mail-deliveries:access', 'global'),
      ('/dashboard/sms-deliveries', 'platform:sms-deliveries:access', 'global'),
      ('/dashboard/event-outbox', 'platform:event-outbox:access', 'global'),
      ('/dashboard/monitoring', 'platform:monitoring:access', 'global'),
      ('/dashboard/api-docs', 'platform:api-docs:access', 'global'),
      ('/dashboard/dictionaries', 'platform:dictionaries:access', 'global'),
      ('/dashboard/branding', 'platform:branding:access', 'global'),
      ('/dashboard/smtp', 'platform:smtp:access', 'global'),
      ('/dashboard/sms', 'platform:sms:access', 'global'),
      ('/dashboard/profile-fields', 'platform:profile-fields:access', 'global'),
      ('/dashboard/agreements', 'platform:agreements:access', 'global'),
      ('/dashboard/watermark', 'platform:watermark:access', 'global'),
      ('/dashboard/maintenance', 'platform:maintenance:access', 'global'),
      ('/dashboard/security', 'platform:security:access', 'global'),
      ('/dashboard/authentication', 'platform:authentication:access', 'global'),
      ('/dashboard/passkey', 'platform:passkey:access', 'global'),
      ('/dashboard/wechat', 'platform:wechat:access', 'global'),
      ('/dashboard/captcha', 'platform:captcha:access', 'global'),
      ('/dashboard/registration', 'platform:registration:access', 'global'),
      ('/dashboard/file-retention', 'platform:file-retention:access', 'global'),
      ('/dashboard/ai-console', 'platform:ai-console:access', 'scoped'),
      ('/dashboard/ai-models', 'platform:ai-models:access', 'global'),
      ('/dashboard/modules', 'platform:modules:access', 'global'),
      ('/dashboard/navigation', 'platform:navigation:access', 'global'),
      ('/dashboard/files-all', 'platform:files-all:access', 'global'),
      ('/dashboard/notifications', 'platform:notifications:access', 'global')
  ) AS route(path, access_permission, access_scope)
 WHERE menu.menu_type = 'PAGE' AND menu.path = route.path;

-- Composite projections are shared by host and plugin authorization. Hidden source tokens never
-- appear in API permission history or catalog; direct/new grants continue to authorize normally.
CREATE OR REPLACE FUNCTION platform_role_logical_authorities(target_role_id uuid)
RETURNS TABLE(authority text)
LANGUAGE sql STABLE
AS $$
WITH target_role AS (
    SELECT data_scope FROM platform_role WHERE id = target_role_id AND enabled
), direct_grants AS (
    SELECT grants.authority::text AS authority
      FROM platform_role_authority grants
     WHERE grants.role_id = target_role_id
       AND grants.authority NOT LIKE 'platform:__v50:%'
       AND NOT EXISTS (
           SELECT 1 FROM platform_permission_retired_authority retired
            WHERE retired.authority = grants.authority
       )
)
SELECT authority FROM direct_grants
UNION
SELECT logical.logical_authority::text
  FROM (SELECT DISTINCT logical_authority FROM platform_permission_projection_component) logical
 WHERE EXISTS (SELECT 1 FROM target_role)
   AND NOT EXISTS (
       SELECT 1
         FROM platform_permission_projection_component component
        WHERE component.logical_authority = logical.logical_authority
          AND NOT EXISTS (
              SELECT 1
                FROM platform_role_authority source
                CROSS JOIN target_role target
               WHERE source.role_id = target_role_id
                 AND source.authority = component.source_authority
                 AND (NOT component.source_requires_global OR target.data_scope = 'ALL')
          )
   )
$$;

CREATE OR REPLACE VIEW platform_effective_authority AS
WITH base_grants AS (
    SELECT grants.user_id, grants.authority
      FROM platform_authority grants
    UNION
    SELECT user_roles.user_id, grants.authority
      FROM platform_user_role user_roles
      JOIN platform_role role ON role.id = user_roles.role_id AND role.enabled
      JOIN platform_role_authority grants ON grants.role_id = role.id
), logical_codes AS (
    SELECT DISTINCT logical_authority FROM platform_permission_projection_component
)
SELECT DISTINCT base.user_id, base.authority
  FROM base_grants base
 WHERE base.authority NOT LIKE 'platform:__v50:%'
   AND NOT EXISTS (
       SELECT 1 FROM platform_permission_retired_authority retired
        WHERE retired.authority = base.authority
   )
UNION
SELECT account.id, logical.logical_authority
  FROM platform_user account
  CROSS JOIN logical_codes logical
 WHERE NOT EXISTS (
       SELECT 1
         FROM platform_permission_projection_component component
        WHERE component.logical_authority = logical.logical_authority
          AND NOT (
              EXISTS (
                  SELECT 1 FROM platform_authority direct_source
                   WHERE direct_source.user_id = account.id
                     AND direct_source.authority = component.source_authority
              )
              OR EXISTS (
                  SELECT 1
                    FROM platform_user_role assigned
                    JOIN platform_role source_role
                      ON source_role.id = assigned.role_id AND source_role.enabled
                    JOIN platform_role_authority role_source
                      ON role_source.role_id = source_role.id
                     AND role_source.authority = component.source_authority
                   WHERE assigned.user_id = account.id
                     AND (NOT component.source_requires_global OR source_role.data_scope = 'ALL')
              )
          )
   );

CREATE OR REPLACE VIEW platform_global_authority AS
WITH base_global AS (
    SELECT grants.user_id, grants.authority
      FROM platform_authority grants
    UNION
    SELECT user_roles.user_id, grants.authority
      FROM platform_user_role user_roles
      JOIN platform_role role
        ON role.id = user_roles.role_id AND role.enabled AND role.data_scope = 'ALL'
      JOIN platform_role_authority grants ON grants.role_id = role.id
), logical_codes AS (
    SELECT DISTINCT logical_authority FROM platform_permission_projection_component
)
SELECT DISTINCT base.user_id, base.authority
  FROM base_global base
 WHERE base.authority NOT LIKE 'platform:__v50:%'
   AND NOT EXISTS (
       SELECT 1 FROM platform_permission_retired_authority retired
        WHERE retired.authority = base.authority
   )
UNION
SELECT account.id, logical.logical_authority
  FROM platform_user account
  CROSS JOIN logical_codes logical
 WHERE NOT EXISTS (
       SELECT 1
         FROM platform_permission_projection_component component
        WHERE component.logical_authority = logical.logical_authority
          AND NOT (
              EXISTS (
                  SELECT 1 FROM platform_authority direct_source
                   WHERE direct_source.user_id = account.id
                     AND direct_source.authority = component.source_authority
              )
              OR EXISTS (
                  SELECT 1
                    FROM platform_user_role assigned
                    JOIN platform_role source_role
                      ON source_role.id = assigned.role_id AND source_role.enabled
                    JOIN platform_role_authority role_source
                      ON role_source.role_id = source_role.id
                     AND role_source.authority = component.source_authority
                   WHERE assigned.user_id = account.id
                     AND source_role.data_scope = 'ALL'
              )
          )
   );

-- Raw-scope visibility remains the existing SELF/ORGANIZATION/SUBTREE/ALL predicate. Composite
-- scoped actions additionally intersect each component explicitly marked as a target-scope source.
CREATE OR REPLACE FUNCTION platform_visible_users_base(actor_name text, permission_name text)
RETURNS TABLE(user_id uuid)
LANGUAGE sql STABLE
AS $$
WITH RECURSIVE actor AS (
    SELECT id FROM platform_user WHERE username = actor_name AND enabled
), scopes AS (
    SELECT role.data_scope
      FROM platform_user_role assigned
      JOIN actor ON actor.id = assigned.user_id
      JOIN platform_role role ON role.id = assigned.role_id AND role.enabled
      JOIN platform_role_authority grants
        ON grants.role_id = role.id AND grants.authority = permission_name
), own_orgs AS (
    SELECT organization.id
      FROM platform_user_organization membership
      JOIN actor ON actor.id = membership.user_id
      JOIN platform_organization organization
        ON organization.id = membership.organization_id AND organization.enabled
), descendants AS (
    SELECT id FROM own_orgs
    UNION
    SELECT organization.id
      FROM platform_organization organization
      JOIN descendants parent ON organization.parent_id = parent.id
     WHERE organization.enabled
), global_scope AS (
    SELECT EXISTS (
               SELECT 1 FROM platform_authority direct
               JOIN actor ON actor.id = direct.user_id
                WHERE direct.authority = permission_name
           ) OR EXISTS (SELECT 1 FROM scopes WHERE data_scope = 'ALL') AS allowed
)
SELECT target.id
  FROM platform_user target
 WHERE target.deleted_at IS NULL
   AND (
       (SELECT allowed FROM global_scope)
       OR (target.id IN (SELECT id FROM actor) AND EXISTS (SELECT 1 FROM scopes))
       OR EXISTS (
           SELECT 1 FROM platform_user_organization membership
            WHERE membership.user_id = target.id
              AND (
                  (EXISTS (SELECT 1 FROM scopes WHERE data_scope = 'ORGANIZATION')
                      AND membership.organization_id IN (SELECT id FROM own_orgs))
                  OR (EXISTS (SELECT 1 FROM scopes WHERE data_scope = 'SUBTREE')
                      AND membership.organization_id IN (SELECT id FROM descendants))
              )
       )
   )
$$;

CREATE OR REPLACE FUNCTION platform_visible_users(actor_name text, permission_name text)
RETURNS TABLE(user_id uuid)
LANGUAGE sql STABLE
AS $$
SELECT visible.user_id
  FROM platform_visible_users_base(actor_name, permission_name) visible
UNION
SELECT target.id
  FROM platform_user target
 WHERE target.deleted_at IS NULL
   AND EXISTS (
       SELECT 1 FROM platform_effective_authority effective
       JOIN platform_user actor ON actor.id = effective.user_id
        WHERE actor.username = actor_name AND actor.enabled
          AND effective.authority = permission_name
   )
   AND EXISTS (
       SELECT 1 FROM platform_permission_projection_component component
        WHERE component.logical_authority = permission_name
          AND component.target_scope_source
   )
   AND NOT EXISTS (
       SELECT 1 FROM platform_permission_projection_component component
        WHERE component.logical_authority = permission_name
          AND component.target_scope_source
          AND target.id NOT IN (
              SELECT visible_source.user_id
                FROM platform_visible_users_base(actor_name, component.source_authority)
                    visible_source
          )
   )
UNION
SELECT target.id
  FROM platform_user target
 WHERE target.deleted_at IS NULL
   AND EXISTS (
       SELECT 1 FROM platform_global_authority global_grant
       JOIN platform_user actor ON actor.id = global_grant.user_id
        WHERE actor.username = actor_name AND actor.enabled
          AND global_grant.authority = permission_name
   )
   AND NOT EXISTS (
       SELECT 1 FROM platform_permission_projection_component component
        WHERE component.logical_authority = permission_name
          AND component.target_scope_source
   )
$$;

CREATE OR REPLACE FUNCTION platform_role_preview_visible_users(
    actor_name text,
    permission_name text,
    target_role_id uuid,
    target_role_version bigint
) RETURNS TABLE(user_id uuid)
LANGUAGE sql STABLE
AS $$
WITH RECURSIVE actor AS (
    SELECT id FROM platform_user
     WHERE username = actor_name AND enabled AND deleted_at IS NULL
), target AS (
    SELECT role.data_scope
      FROM platform_role role
     WHERE role.id = target_role_id AND role.enabled AND role.version = target_role_version
       AND EXISTS (
           SELECT 1 FROM platform_role_logical_authorities(role.id) grants
            WHERE grants.authority = permission_name
       )
), own_orgs AS (
    SELECT organization.id
      FROM platform_user_organization membership
      JOIN actor ON actor.id = membership.user_id
      JOIN platform_organization organization
        ON organization.id = membership.organization_id AND organization.enabled
), descendants AS (
    SELECT id FROM own_orgs
    UNION
    SELECT organization.id
      FROM platform_organization organization
      JOIN descendants parent ON organization.parent_id = parent.id
     WHERE organization.enabled
), target_visible AS (
    SELECT target_user.id
      FROM platform_user target_user
      CROSS JOIN target scope
     WHERE target_user.deleted_at IS NULL
       AND (
           scope.data_scope = 'ALL'
           OR target_user.id IN (SELECT id FROM actor)
           OR (scope.data_scope IN ('ORGANIZATION', 'SUBTREE') AND EXISTS (
               SELECT 1 FROM platform_user_organization membership
                WHERE membership.user_id = target_user.id
                  AND membership.organization_id IN (
                      SELECT id FROM own_orgs
                      UNION
                      SELECT id FROM descendants WHERE scope.data_scope = 'SUBTREE'
                  )
           ))
       )
)
SELECT real.user_id
  FROM platform_visible_users(actor_name, permission_name) real
 WHERE target_role_id IS NULL
UNION
SELECT real.user_id
  FROM platform_visible_users(actor_name, permission_name) real
  JOIN target_visible target ON target.id = real.user_id
$$;

-- All recovery capabilities are represented as a complete, global administrator identity.
-- Page/action conjunctions are checked for the same enabled account.
CREATE OR REPLACE VIEW platform_recovery_capability AS
SELECT account.id AS user_id, 'platform:recovery:administrator'::character varying AS capability
  FROM platform_user account
 WHERE account.enabled AND account.deleted_at IS NULL
   AND NOT EXISTS (
       SELECT required.authority
         FROM (VALUES
             ('platform:users:access'),
             ('platform:users:create'),
             ('platform:users:update'),
             ('platform:users:delete'),
             ('platform:users:assign-roles'),
             ('platform:roles:access'),
             ('platform:roles:create'),
             ('platform:roles:update'),
             ('platform:roles:delete'),
             ('platform:organizations:access'),
             ('platform:organizations:create'),
             ('platform:organizations:update'),
             ('platform:organizations:delete'),
             ('platform:security:access'),
             ('platform:security:update')
         ) required(authority)
        WHERE NOT EXISTS (
            SELECT 1 FROM platform_global_authority global_grant
             WHERE global_grant.user_id = account.id
               AND global_grant.authority = required.authority
        )
   );

CREATE OR REPLACE FUNCTION platform_has_recovery_administrators() RETURNS boolean
LANGUAGE sql STABLE
AS $$
SELECT EXISTS (
    SELECT 1 FROM platform_recovery_capability capability
    JOIN platform_user account ON account.id = capability.user_id
    WHERE account.enabled AND account.deleted_at IS NULL
)
$$;

CREATE OR REPLACE FUNCTION platform_is_last_recovery_administrator(target uuid) RETURNS boolean
LANGUAGE sql STABLE
AS $$
SELECT EXISTS (
    SELECT 1 FROM platform_recovery_capability own
     WHERE own.user_id = target
       AND NOT EXISTS (
           SELECT 1 FROM platform_recovery_capability other
           JOIN platform_user account ON account.id = other.user_id
            WHERE account.enabled AND account.deleted_at IS NULL
              AND account.id <> target
       )
)
$$;
