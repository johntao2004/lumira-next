-- Preserve existing payment-core order grants with their original role/direct scope.
WITH permission_map(source, target) AS (
    VALUES
        ('platform:payment-core:read', 'platform:payment-core:orders-access'),
        ('platform:payment-core:read', 'platform:payment-core:orders-read'),
        ('platform:payment-core:order-create', 'platform:payment-core:orders-access'),
        ('platform:payment-core:order-create', 'platform:payment-core:orders-create'),
        ('platform:payment-core:order-manage', 'platform:payment-core:orders-access'),
        ('platform:payment-core:order-manage', 'platform:payment-core:orders-manage'),
        ('platform:payment-core:refund', 'platform:payment-core:orders-access'),
        ('platform:payment-core:refund', 'platform:payment-core:orders-refund'),
        ('platform:payment-core:create', 'platform:payment-core:orders-access'),
        ('platform:payment-core:create', 'platform:payment-core:orders-create'),
        ('platform:payment-core:manage', 'platform:payment-core:orders-access'),
        ('platform:payment-core:manage', 'platform:payment-core:orders-manage')
)
INSERT INTO platform_role_authority(role_id, authority)
SELECT DISTINCT existing.role_id, mapping.target
  FROM platform_role_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;

WITH permission_map(source, target) AS (
    VALUES
        ('platform:payment-core:read', 'platform:payment-core:orders-access'),
        ('platform:payment-core:read', 'platform:payment-core:orders-read'),
        ('platform:payment-core:order-create', 'platform:payment-core:orders-access'),
        ('platform:payment-core:order-create', 'platform:payment-core:orders-create'),
        ('platform:payment-core:order-manage', 'platform:payment-core:orders-access'),
        ('platform:payment-core:order-manage', 'platform:payment-core:orders-manage'),
        ('platform:payment-core:refund', 'platform:payment-core:orders-access'),
        ('platform:payment-core:refund', 'platform:payment-core:orders-refund'),
        ('platform:payment-core:create', 'platform:payment-core:orders-access'),
        ('platform:payment-core:create', 'platform:payment-core:orders-create'),
        ('platform:payment-core:manage', 'platform:payment-core:orders-access'),
        ('platform:payment-core:manage', 'platform:payment-core:orders-manage')
)
INSERT INTO platform_authority(user_id, authority)
SELECT DISTINCT existing.user_id, mapping.target
  FROM platform_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;
