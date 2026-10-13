-- Migrate old feedback grants without widening the former manager API semantics.
-- V49 manager reads required global read-all. Manager updates required global read-all plus the
-- field-specific global update/reply action. The opaque sources below preserve those legacy
-- combinations while the new logical update and reply grants remain independent.

INSERT INTO platform_permission_projection_component(
    logical_authority, source_authority, source_requires_global, target_scope_source
) VALUES
    ('platform:feedback:read-all', 'platform:__v50:feedback-read-all-source', true, false),
    ('platform:feedback:update', 'platform:__v50:feedback-update-source', true, false),
    ('platform:feedback:update', 'platform:__v50:feedback-update-read-all-source', true, false),
    ('platform:feedback:reply', 'platform:__v50:feedback-reply-source', true, false),
    ('platform:feedback:reply', 'platform:__v50:feedback-reply-read-all-source', true, false)
ON CONFLICT DO NOTHING;

-- Preserve the page and scoped self-service capabilities. Legacy read-all also gave access to the
-- management page, while only its opaque projection can satisfy the new management operation.
WITH permission_map(source_authority, target_authority) AS (
    VALUES
        ('platform:feedback:read', 'platform:feedback:access'),
        ('platform:feedback:create', 'platform:feedback:access'),
        ('platform:feedback:read-all', 'platform:feedback:access'),
        ('platform:feedback:manage', 'platform:feedback:access'),
        ('platform:feedback:read', 'platform:feedback:read'),
        ('platform:feedback:create', 'platform:feedback:create'),
        ('platform:feedback:read-all', 'platform:__v50:feedback-read-all-source'),
        ('platform:feedback:read-all', 'platform:__v50:feedback-update-read-all-source'),
        ('platform:feedback:read-all', 'platform:__v50:feedback-reply-read-all-source'),
        ('platform:feedback:update', 'platform:__v50:feedback-update-source'),
        ('platform:feedback:reply', 'platform:__v50:feedback-reply-source'),
        ('platform:feedback:manage', 'platform:__v50:feedback-read-all-source'),
        ('platform:feedback:manage', 'platform:__v50:feedback-update-source'),
        ('platform:feedback:manage', 'platform:__v50:feedback-update-read-all-source'),
        ('platform:feedback:manage', 'platform:__v50:feedback-reply-source'),
        ('platform:feedback:manage', 'platform:__v50:feedback-reply-read-all-source')
)
INSERT INTO platform_role_authority(role_id, authority)
SELECT DISTINCT existing.role_id, mapping.target_authority
  FROM platform_role_authority existing
  JOIN permission_map mapping ON mapping.source_authority = existing.authority
ON CONFLICT DO NOTHING;

WITH permission_map(source_authority, target_authority) AS (
    VALUES
        ('platform:feedback:read', 'platform:feedback:access'),
        ('platform:feedback:create', 'platform:feedback:access'),
        ('platform:feedback:read-all', 'platform:feedback:access'),
        ('platform:feedback:manage', 'platform:feedback:access'),
        ('platform:feedback:read', 'platform:feedback:read'),
        ('platform:feedback:create', 'platform:feedback:create'),
        ('platform:feedback:read-all', 'platform:__v50:feedback-read-all-source'),
        ('platform:feedback:read-all', 'platform:__v50:feedback-update-read-all-source'),
        ('platform:feedback:read-all', 'platform:__v50:feedback-reply-read-all-source'),
        ('platform:feedback:update', 'platform:__v50:feedback-update-source'),
        ('platform:feedback:reply', 'platform:__v50:feedback-reply-source'),
        ('platform:feedback:manage', 'platform:__v50:feedback-read-all-source'),
        ('platform:feedback:manage', 'platform:__v50:feedback-update-source'),
        ('platform:feedback:manage', 'platform:__v50:feedback-update-read-all-source'),
        ('platform:feedback:manage', 'platform:__v50:feedback-reply-source'),
        ('platform:feedback:manage', 'platform:__v50:feedback-reply-read-all-source')
)
INSERT INTO platform_authority(user_id, authority)
SELECT DISTINCT existing.user_id, mapping.target_authority
  FROM platform_authority existing
  JOIN permission_map mapping ON mapping.source_authority = existing.authority
ON CONFLICT DO NOTHING;

-- Keep active logical operation names available for new explicit grants. Only the obsolete
-- aggregate manage code is retired after its old grants have been converted to hidden sources.
INSERT INTO platform_permission_retired_authority(authority)
VALUES ('platform:feedback:manage')
ON CONFLICT DO NOTHING;

DELETE FROM platform_role_authority
 WHERE authority IN (
    'platform:feedback:read-all',
    'platform:feedback:update',
    'platform:feedback:reply',
    'platform:feedback:manage'
);

DELETE FROM platform_authority
 WHERE authority IN (
    'platform:feedback:read-all',
    'platform:feedback:update',
    'platform:feedback:reply',
    'platform:feedback:manage'
);
