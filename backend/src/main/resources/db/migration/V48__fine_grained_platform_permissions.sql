-- Expand only the legacy permissions whose former service guards are known. Unknown third-party
-- write/manage codes are intentionally left unchanged and are not added to the active catalog.
WITH permission_alias(old_authority, new_authority) AS (
    VALUES
        ('platform:users:write', 'platform:users:create'),
        ('platform:users:write', 'platform:users:update'),
        ('platform:users:write', 'platform:users:delete'),
        ('platform:users:write', 'platform:users:terminate-session'),
        ('platform:roles:write', 'platform:roles:create'),
        ('platform:roles:write', 'platform:roles:update'),
        ('platform:roles:write', 'platform:roles:delete'),
        ('platform:roles:write', 'platform:roles:assign'),
        ('platform:organizations:write', 'platform:organizations:create'),
        ('platform:organizations:write', 'platform:organizations:update'),
        ('platform:organizations:write', 'platform:organizations:delete'),
        ('platform:organizations:write', 'platform:organizations:assign'),
        ('platform:dictionaries:write', 'platform:dictionaries:create'),
        ('platform:dictionaries:write', 'platform:dictionaries:update'),
        ('platform:dictionaries:write', 'platform:dictionaries:delete'),
        ('platform:dictionaries:write', 'platform:dictionaries:import'),
        ('platform:settings:write', 'platform:settings:create'),
        ('platform:settings:write', 'platform:settings:update'),
        ('platform:settings:write', 'platform:settings:delete'),
        ('platform:settings:write', 'platform:settings:read'),
        ('platform:modules:write', 'platform:modules:install'),
        ('platform:modules:write', 'platform:modules:update'),
        ('platform:modules:write', 'platform:modules:uninstall'),
        ('platform:events:write', 'platform:events:replay'),
        ('platform:navigation:write', 'platform:navigation:create'),
        ('platform:navigation:write', 'platform:navigation:update'),
        ('platform:navigation:write', 'platform:navigation:delete'),
        ('platform:files:write', 'platform:files:delete'),
        ('platform:files:write', 'platform:files:restore'),
        ('platform:notifications:write', 'platform:notifications:create'),
        ('platform:feedback:manage', 'platform:feedback:read-all'),
        ('platform:feedback:manage', 'platform:feedback:update'),
        ('platform:feedback:manage', 'platform:feedback:reply'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:create'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:update'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:delete'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:policy-update'),
        ('platform:localization:write', 'platform:localization:create'),
        ('platform:localization:write', 'platform:localization:update'),
        ('platform:localization:write', 'platform:localization:delete'),
        ('platform:localization:write', 'platform:localization:publish'),
        ('platform:mfa:manage', 'platform:mfa:read'),
        ('platform:mfa:manage', 'platform:mfa:reset'),
        ('platform:payment-core:manage', 'platform:payment-core:settings-update'),
        ('platform:payment-core:manage', 'platform:payment-core:order-manage'),
        ('platform:payment-core:create', 'platform:payment-core:order-create')
)
INSERT INTO platform_role_authority(role_id, authority)
SELECT DISTINCT grants.role_id, permission_alias.new_authority
FROM platform_role_authority grants
JOIN permission_alias ON permission_alias.old_authority = grants.authority
ON CONFLICT DO NOTHING;

WITH permission_alias(old_authority, new_authority) AS (
    VALUES
        ('platform:users:write', 'platform:users:create'),
        ('platform:users:write', 'platform:users:update'),
        ('platform:users:write', 'platform:users:delete'),
        ('platform:users:write', 'platform:users:terminate-session'),
        ('platform:roles:write', 'platform:roles:create'),
        ('platform:roles:write', 'platform:roles:update'),
        ('platform:roles:write', 'platform:roles:delete'),
        ('platform:roles:write', 'platform:roles:assign'),
        ('platform:organizations:write', 'platform:organizations:create'),
        ('platform:organizations:write', 'platform:organizations:update'),
        ('platform:organizations:write', 'platform:organizations:delete'),
        ('platform:organizations:write', 'platform:organizations:assign'),
        ('platform:dictionaries:write', 'platform:dictionaries:create'),
        ('platform:dictionaries:write', 'platform:dictionaries:update'),
        ('platform:dictionaries:write', 'platform:dictionaries:delete'),
        ('platform:dictionaries:write', 'platform:dictionaries:import'),
        ('platform:settings:write', 'platform:settings:create'),
        ('platform:settings:write', 'platform:settings:update'),
        ('platform:settings:write', 'platform:settings:delete'),
        ('platform:settings:write', 'platform:settings:read'),
        ('platform:modules:write', 'platform:modules:install'),
        ('platform:modules:write', 'platform:modules:update'),
        ('platform:modules:write', 'platform:modules:uninstall'),
        ('platform:events:write', 'platform:events:replay'),
        ('platform:navigation:write', 'platform:navigation:create'),
        ('platform:navigation:write', 'platform:navigation:update'),
        ('platform:navigation:write', 'platform:navigation:delete'),
        ('platform:files:write', 'platform:files:delete'),
        ('platform:files:write', 'platform:files:restore'),
        ('platform:notifications:write', 'platform:notifications:create'),
        ('platform:feedback:manage', 'platform:feedback:read-all'),
        ('platform:feedback:manage', 'platform:feedback:update'),
        ('platform:feedback:manage', 'platform:feedback:reply'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:create'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:update'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:delete'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:policy-update'),
        ('platform:localization:write', 'platform:localization:create'),
        ('platform:localization:write', 'platform:localization:update'),
        ('platform:localization:write', 'platform:localization:delete'),
        ('platform:localization:write', 'platform:localization:publish'),
        ('platform:mfa:manage', 'platform:mfa:read'),
        ('platform:mfa:manage', 'platform:mfa:reset'),
        ('platform:payment-core:manage', 'platform:payment-core:settings-update'),
        ('platform:payment-core:manage', 'platform:payment-core:order-manage'),
        ('platform:payment-core:create', 'platform:payment-core:order-create')
)
INSERT INTO platform_authority(user_id, authority)
SELECT DISTINCT grants.user_id, permission_alias.new_authority
FROM platform_authority grants
JOIN permission_alias ON permission_alias.old_authority = grants.authority
ON CONFLICT DO NOTHING;

DELETE FROM platform_role_authority
WHERE authority IN (
    'platform:users:write',
    'platform:roles:write',
    'platform:organizations:write',
    'platform:dictionaries:write',
    'platform:settings:write',
    'platform:modules:write',
    'platform:events:write',
    'platform:navigation:write',
    'platform:files:write',
    'platform:notifications:write',
    'platform:feedback:manage',
    'platform:sensitive-words:write',
    'platform:localization:write',
    'platform:mfa:manage',
    'platform:payment-core:manage',
    'platform:payment-core:create'
);

DELETE FROM platform_authority
WHERE authority IN (
    'platform:users:write',
    'platform:roles:write',
    'platform:organizations:write',
    'platform:dictionaries:write',
    'platform:settings:write',
    'platform:modules:write',
    'platform:events:write',
    'platform:navigation:write',
    'platform:files:write',
    'platform:notifications:write',
    'platform:feedback:manage',
    'platform:sensitive-words:write',
    'platform:localization:write',
    'platform:mfa:manage',
    'platform:payment-core:manage',
    'platform:payment-core:create'
);

CREATE OR REPLACE VIEW platform_recovery_capability AS
SELECT user_id, authority AS capability
FROM platform_global_authority g
WHERE authority IN (
    'platform:users:create',
    'platform:users:update',
    'platform:users:delete',
    'platform:roles:create',
    'platform:roles:update',
    'platform:roles:delete',
    'platform:roles:assign'
)
OR (
    authority IN (
        'platform:settings:create',
        'platform:settings:update',
        'platform:settings:delete'
    )
    AND EXISTS (
        SELECT 1
        FROM platform_effective_authority a
        WHERE a.user_id = g.user_id
          AND a.authority = 'platform:settings:read'
    )
);

CREATE OR REPLACE FUNCTION public.platform_has_recovery_administrators() RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
 SELECT NOT EXISTS(
  SELECT 1 FROM (VALUES
    ('platform:users:create'),
    ('platform:users:update'),
    ('platform:users:delete'),
    ('platform:roles:create'),
    ('platform:roles:update'),
    ('platform:roles:delete'),
    ('platform:roles:assign'),
    ('platform:settings:create'),
    ('platform:settings:update'),
    ('platform:settings:delete')
  ) required(capability)
  WHERE NOT EXISTS(SELECT 1 FROM platform_recovery_capability c JOIN platform_user u ON u.id=c.user_id
   WHERE u.enabled AND c.capability=required.capability))
$$;
