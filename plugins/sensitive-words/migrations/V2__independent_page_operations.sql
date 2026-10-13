-- Preserve each authority's existing role scope or direct-user scope.
WITH permission_map(source, target) AS (
    VALUES
        ('platform:sensitive-words:read', 'platform:sensitive-words:access'),
        ('platform:sensitive-words:read', 'platform:sensitive-words:read'),
        ('platform:sensitive-words:create', 'platform:sensitive-words:access'),
        ('platform:sensitive-words:create', 'platform:sensitive-words:create'),
        ('platform:sensitive-words:update', 'platform:sensitive-words:access'),
        ('platform:sensitive-words:update', 'platform:sensitive-words:update'),
        ('platform:sensitive-words:delete', 'platform:sensitive-words:access'),
        ('platform:sensitive-words:delete', 'platform:sensitive-words:delete'),
        ('platform:sensitive-words:import', 'platform:sensitive-words:access'),
        ('platform:sensitive-words:import', 'platform:sensitive-words:import'),
        ('platform:sensitive-words:policy-update', 'platform:sensitive-words:access'),
        ('platform:sensitive-words:policy-update', 'platform:sensitive-words:policy-update'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:access'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:create'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:update'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:delete'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:policy-update')
)
INSERT INTO platform_role_authority(role_id, authority)
SELECT DISTINCT existing.role_id, mapping.target
  FROM platform_role_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;

WITH permission_map(source, target) AS (
    VALUES
        ('platform:sensitive-words:read', 'platform:sensitive-words:access'),
        ('platform:sensitive-words:read', 'platform:sensitive-words:read'),
        ('platform:sensitive-words:create', 'platform:sensitive-words:access'),
        ('platform:sensitive-words:create', 'platform:sensitive-words:create'),
        ('platform:sensitive-words:update', 'platform:sensitive-words:access'),
        ('platform:sensitive-words:update', 'platform:sensitive-words:update'),
        ('platform:sensitive-words:delete', 'platform:sensitive-words:access'),
        ('platform:sensitive-words:delete', 'platform:sensitive-words:delete'),
        ('platform:sensitive-words:import', 'platform:sensitive-words:access'),
        ('platform:sensitive-words:import', 'platform:sensitive-words:import'),
        ('platform:sensitive-words:policy-update', 'platform:sensitive-words:access'),
        ('platform:sensitive-words:policy-update', 'platform:sensitive-words:policy-update'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:access'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:create'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:update'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:delete'),
        ('platform:sensitive-words:write', 'platform:sensitive-words:policy-update')
)
INSERT INTO platform_authority(user_id, authority)
SELECT DISTINCT existing.user_id, mapping.target
  FROM platform_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;

-- Retire the former aggregate after its scope-preserving operation grants have been copied.
INSERT INTO platform_permission_retired_authority(authority)
VALUES ('platform:sensitive-words:write')
ON CONFLICT DO NOTHING;

DELETE FROM platform_role_authority
 WHERE authority = 'platform:sensitive-words:write';

DELETE FROM platform_authority
 WHERE authority = 'platform:sensitive-words:write';
