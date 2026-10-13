-- Keep prior shared payment access, order actions and settings rights on this provider page.
WITH permission_map(source, target) AS (
    VALUES
        ('platform:payment-core:read', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:read', 'platform:wechat-pay:orders-read'),
        ('platform:payment-core:read', 'platform:wechat-pay:settings-access'),
        ('platform:payment-core:read', 'platform:wechat-pay:settings-read'),
        ('platform:payment-core:order-create', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:order-create', 'platform:wechat-pay:orders-create'),
        ('platform:payment-core:order-manage', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:order-manage', 'platform:wechat-pay:orders-manage'),
        ('platform:payment-core:refund', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:refund', 'platform:wechat-pay:orders-refund'),
        ('platform:payment-core:settings-update', 'platform:wechat-pay:settings-access'),
        ('platform:payment-core:settings-update', 'platform:wechat-pay:settings-update'),
        ('platform:payment-core:create', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:create', 'platform:wechat-pay:orders-create'),
        ('platform:payment-core:manage', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:manage', 'platform:wechat-pay:orders-manage')
)
INSERT INTO platform_role_authority(role_id, authority)
SELECT DISTINCT existing.role_id, mapping.target
  FROM platform_role_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;

WITH permission_map(source, target) AS (
    VALUES
        ('platform:payment-core:read', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:read', 'platform:wechat-pay:orders-read'),
        ('platform:payment-core:read', 'platform:wechat-pay:settings-access'),
        ('platform:payment-core:read', 'platform:wechat-pay:settings-read'),
        ('platform:payment-core:order-create', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:order-create', 'platform:wechat-pay:orders-create'),
        ('platform:payment-core:order-manage', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:order-manage', 'platform:wechat-pay:orders-manage'),
        ('platform:payment-core:refund', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:refund', 'platform:wechat-pay:orders-refund'),
        ('platform:payment-core:settings-update', 'platform:wechat-pay:settings-access'),
        ('platform:payment-core:settings-update', 'platform:wechat-pay:settings-update'),
        ('platform:payment-core:create', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:create', 'platform:wechat-pay:orders-create'),
        ('platform:payment-core:manage', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:manage', 'platform:wechat-pay:orders-manage')
)
INSERT INTO platform_authority(user_id, authority)
SELECT DISTINCT existing.user_id, mapping.target
  FROM platform_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;
