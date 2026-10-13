-- Keep prior shared payment access, order actions and settings rights on this provider page.
WITH permission_map(source, target) AS (
    VALUES
        ('platform:payment-core:read', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:read', 'platform:alipay-pay:orders-read'),
        ('platform:payment-core:read', 'platform:alipay-pay:settings-access'),
        ('platform:payment-core:read', 'platform:alipay-pay:settings-read'),
        ('platform:payment-core:order-create', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:order-create', 'platform:alipay-pay:orders-create'),
        ('platform:payment-core:order-manage', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:order-manage', 'platform:alipay-pay:orders-manage'),
        ('platform:payment-core:refund', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:refund', 'platform:alipay-pay:orders-refund'),
        ('platform:payment-core:settings-update', 'platform:alipay-pay:settings-access'),
        ('platform:payment-core:settings-update', 'platform:alipay-pay:settings-update'),
        ('platform:payment-core:create', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:create', 'platform:alipay-pay:orders-create'),
        ('platform:payment-core:manage', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:manage', 'platform:alipay-pay:orders-manage')
)
INSERT INTO platform_role_authority(role_id, authority)
SELECT DISTINCT existing.role_id, mapping.target
  FROM platform_role_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;

WITH permission_map(source, target) AS (
    VALUES
        ('platform:payment-core:read', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:read', 'platform:alipay-pay:orders-read'),
        ('platform:payment-core:read', 'platform:alipay-pay:settings-access'),
        ('platform:payment-core:read', 'platform:alipay-pay:settings-read'),
        ('platform:payment-core:order-create', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:order-create', 'platform:alipay-pay:orders-create'),
        ('platform:payment-core:order-manage', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:order-manage', 'platform:alipay-pay:orders-manage'),
        ('platform:payment-core:refund', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:refund', 'platform:alipay-pay:orders-refund'),
        ('platform:payment-core:settings-update', 'platform:alipay-pay:settings-access'),
        ('platform:payment-core:settings-update', 'platform:alipay-pay:settings-update'),
        ('platform:payment-core:create', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:create', 'platform:alipay-pay:orders-create'),
        ('platform:payment-core:manage', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:manage', 'platform:alipay-pay:orders-manage')
)
INSERT INTO platform_authority(user_id, authority)
SELECT DISTINCT existing.user_id, mapping.target
  FROM platform_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;
