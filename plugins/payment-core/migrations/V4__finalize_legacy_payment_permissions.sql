-- V3 and the published provider V1 migrations preserve the legacy shared permission scopes.
-- Providers depend on payment-core and therefore upgrade afterwards, so preserve their legacy
-- grants here as well; otherwise this helper would retire the old shared sources before an
-- absent provider could run its own V1 conversion on a later install.
DO $$
BEGIN
    IF to_regprocedure('public.platform_normalize_legacy_permission_projections()') IS NULL THEN
        RAISE EXCEPTION 'payment-core permission finalization requires platform migration V51';
    END IF;
END
$$;

WITH permission_map(source, target) AS (
    VALUES
        ('platform:payment-core:read', 'platform:payment-core:orders-access'),
        ('platform:payment-core:read', 'platform:payment-core:orders-read'),
        ('platform:payment-core:read', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:read', 'platform:wechat-pay:orders-read'),
        ('platform:payment-core:read', 'platform:wechat-pay:settings-access'),
        ('platform:payment-core:read', 'platform:wechat-pay:settings-read'),
        ('platform:payment-core:read', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:read', 'platform:alipay-pay:orders-read'),
        ('platform:payment-core:read', 'platform:alipay-pay:settings-access'),
        ('platform:payment-core:read', 'platform:alipay-pay:settings-read'),
        ('platform:payment-core:create', 'platform:payment-core:orders-access'),
        ('platform:payment-core:create', 'platform:payment-core:orders-create'),
        ('platform:payment-core:create', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:create', 'platform:wechat-pay:orders-create'),
        ('platform:payment-core:create', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:create', 'platform:alipay-pay:orders-create'),
        ('platform:payment-core:order-create', 'platform:payment-core:orders-access'),
        ('platform:payment-core:order-create', 'platform:payment-core:orders-create'),
        ('platform:payment-core:order-create', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:order-create', 'platform:wechat-pay:orders-create'),
        ('platform:payment-core:order-create', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:order-create', 'platform:alipay-pay:orders-create'),
        ('platform:payment-core:manage', 'platform:payment-core:orders-access'),
        ('platform:payment-core:manage', 'platform:payment-core:orders-manage'),
        ('platform:payment-core:manage', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:manage', 'platform:wechat-pay:orders-manage'),
        ('platform:payment-core:manage', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:manage', 'platform:alipay-pay:orders-manage'),
        ('platform:payment-core:order-manage', 'platform:payment-core:orders-access'),
        ('platform:payment-core:order-manage', 'platform:payment-core:orders-manage'),
        ('platform:payment-core:order-manage', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:order-manage', 'platform:wechat-pay:orders-manage'),
        ('platform:payment-core:order-manage', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:order-manage', 'platform:alipay-pay:orders-manage'),
        ('platform:payment-core:refund', 'platform:payment-core:orders-access'),
        ('platform:payment-core:refund', 'platform:payment-core:orders-refund'),
        ('platform:payment-core:refund', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:refund', 'platform:wechat-pay:orders-refund'),
        ('platform:payment-core:refund', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:refund', 'platform:alipay-pay:orders-refund'),
        ('platform:payment-core:settings-update', 'platform:wechat-pay:settings-access'),
        ('platform:payment-core:settings-update', 'platform:wechat-pay:settings-update'),
        ('platform:payment-core:settings-update', 'platform:alipay-pay:settings-access'),
        ('platform:payment-core:settings-update', 'platform:alipay-pay:settings-update')
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
        ('platform:payment-core:read', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:read', 'platform:wechat-pay:orders-read'),
        ('platform:payment-core:read', 'platform:wechat-pay:settings-access'),
        ('platform:payment-core:read', 'platform:wechat-pay:settings-read'),
        ('platform:payment-core:read', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:read', 'platform:alipay-pay:orders-read'),
        ('platform:payment-core:read', 'platform:alipay-pay:settings-access'),
        ('platform:payment-core:read', 'platform:alipay-pay:settings-read'),
        ('platform:payment-core:create', 'platform:payment-core:orders-access'),
        ('platform:payment-core:create', 'platform:payment-core:orders-create'),
        ('platform:payment-core:create', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:create', 'platform:wechat-pay:orders-create'),
        ('platform:payment-core:create', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:create', 'platform:alipay-pay:orders-create'),
        ('platform:payment-core:order-create', 'platform:payment-core:orders-access'),
        ('platform:payment-core:order-create', 'platform:payment-core:orders-create'),
        ('platform:payment-core:order-create', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:order-create', 'platform:wechat-pay:orders-create'),
        ('platform:payment-core:order-create', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:order-create', 'platform:alipay-pay:orders-create'),
        ('platform:payment-core:manage', 'platform:payment-core:orders-access'),
        ('platform:payment-core:manage', 'platform:payment-core:orders-manage'),
        ('platform:payment-core:manage', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:manage', 'platform:wechat-pay:orders-manage'),
        ('platform:payment-core:manage', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:manage', 'platform:alipay-pay:orders-manage'),
        ('platform:payment-core:order-manage', 'platform:payment-core:orders-access'),
        ('platform:payment-core:order-manage', 'platform:payment-core:orders-manage'),
        ('platform:payment-core:order-manage', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:order-manage', 'platform:wechat-pay:orders-manage'),
        ('platform:payment-core:order-manage', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:order-manage', 'platform:alipay-pay:orders-manage'),
        ('platform:payment-core:refund', 'platform:payment-core:orders-access'),
        ('platform:payment-core:refund', 'platform:payment-core:orders-refund'),
        ('platform:payment-core:refund', 'platform:wechat-pay:orders-access'),
        ('platform:payment-core:refund', 'platform:wechat-pay:orders-refund'),
        ('platform:payment-core:refund', 'platform:alipay-pay:orders-access'),
        ('platform:payment-core:refund', 'platform:alipay-pay:orders-refund'),
        ('platform:payment-core:settings-update', 'platform:wechat-pay:settings-access'),
        ('platform:payment-core:settings-update', 'platform:wechat-pay:settings-update'),
        ('platform:payment-core:settings-update', 'platform:alipay-pay:settings-access'),
        ('platform:payment-core:settings-update', 'platform:alipay-pay:settings-update')
)
INSERT INTO platform_authority(user_id, authority)
SELECT DISTINCT existing.user_id, mapping.target
  FROM platform_authority existing
  JOIN permission_map mapping ON mapping.source = existing.authority
ON CONFLICT DO NOTHING;

-- These shared pre-v2 permissions are replaced by independent order and provider grants above.
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

-- Require the host migration explicitly. A missing V51 helper is not a successful no-op because
-- legacy payment grants would otherwise remain usable on an older host.
SELECT public.platform_normalize_legacy_permission_projections();
