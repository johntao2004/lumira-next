-- Keep each existing scope and grant while adding its new page-access prerequisite.
WITH permission_map(source, target) AS (
    VALUES
        ('platform:localization:read', 'platform:localization:access'),
        ('platform:localization:read', 'platform:localization:read'),
        ('platform:localization:create', 'platform:localization:access'),
        ('platform:localization:create', 'platform:localization:create'),
        ('platform:localization:update', 'platform:localization:access'),
        ('platform:localization:update', 'platform:localization:update'),
        ('platform:localization:delete', 'platform:localization:access'),
        ('platform:localization:delete', 'platform:localization:delete'),
        ('platform:localization:import', 'platform:localization:access'),
        ('platform:localization:import', 'platform:localization:import'),
        ('platform:localization:publish', 'platform:localization:access'),
        ('platform:localization:publish', 'platform:localization:publish'),
        ('platform:localization:write', 'platform:localization:access'),
        ('platform:localization:write', 'platform:localization:create'),
        ('platform:localization:write', 'platform:localization:update'),
        ('platform:localization:write', 'platform:localization:delete'),
        ('platform:localization:write', 'platform:localization:publish')
)
INSERT INTO platform_role_authority(role_id, authority)
SELECT DISTINCT existing.role_id, mapping.target
  FROM platform_role_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;

WITH permission_map(source, target) AS (
    VALUES
        ('platform:localization:read', 'platform:localization:access'),
        ('platform:localization:read', 'platform:localization:read'),
        ('platform:localization:create', 'platform:localization:access'),
        ('platform:localization:create', 'platform:localization:create'),
        ('platform:localization:update', 'platform:localization:access'),
        ('platform:localization:update', 'platform:localization:update'),
        ('platform:localization:delete', 'platform:localization:access'),
        ('platform:localization:delete', 'platform:localization:delete'),
        ('platform:localization:import', 'platform:localization:access'),
        ('platform:localization:import', 'platform:localization:import'),
        ('platform:localization:publish', 'platform:localization:access'),
        ('platform:localization:publish', 'platform:localization:publish'),
        ('platform:localization:write', 'platform:localization:access'),
        ('platform:localization:write', 'platform:localization:create'),
        ('platform:localization:write', 'platform:localization:update'),
        ('platform:localization:write', 'platform:localization:delete'),
        ('platform:localization:write', 'platform:localization:publish')
)
INSERT INTO platform_authority(user_id, authority)
SELECT DISTINCT existing.user_id, mapping.target
  FROM platform_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;

-- Retire the former aggregate after its scope-preserving operation grants have been copied.
INSERT INTO platform_permission_retired_authority(authority)
VALUES ('platform:localization:write')
ON CONFLICT DO NOTHING;

DELETE FROM platform_role_authority
 WHERE authority = 'platform:localization:write';

DELETE FROM platform_authority
 WHERE authority = 'platform:localization:write';
