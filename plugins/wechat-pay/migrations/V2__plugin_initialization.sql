-- Final-state initialization for wechat-pay on the platform B52 host.
-- Existing completed installations validate published history without replaying this SQL.

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

-- V1 is published and maps legacy shared payment grants to this provider's own pages/actions.
-- Run before the host retires obsolete shared authorities on fresh or upgraded installations.
DO $$
BEGIN
    IF to_regprocedure('public.platform_normalize_legacy_permission_projections()') IS NULL THEN
        RAISE EXCEPTION 'wechat-pay permission finalization requires platform migration V51';
    END IF;
END
$$;

INSERT INTO platform_permission_retired_authority(authority)
VALUES
    ('platform:payment-core:read'),
    ('platform:payment-core:create'),
    ('platform:payment-core:manage'),
    ('platform:payment-core:refund'),
    ('platform:payment-core:settings-update'),
    ('platform:payment-core:order-create'),
    ('platform:payment-core:order-manage')
ON CONFLICT DO NOTHING;

SELECT public.platform_normalize_legacy_permission_projections();
