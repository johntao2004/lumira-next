-- Final-state initialization for payment-core on the platform B52 host.
-- Existing completed installations validate published history without replaying this SQL.

CREATE TABLE plugin_payment_core_settings (
    provider_id varchar(32) PRIMARY KEY CHECK (provider_id ~ '^[a-z][a-z0-9-]{0,31}$'),
    current_version bigint NOT NULL CHECK (current_version >= 0),
    values_json text NOT NULL,
    updated_by varchar(100) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE plugin_payment_core_settings_history (
    provider_id varchar(32) NOT NULL CHECK (provider_id ~ '^[a-z][a-z0-9-]{0,31}$'),
    version bigint NOT NULL CHECK (version > 0),
    values_json text NOT NULL,
    created_by varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (provider_id, version)
);

CREATE TABLE plugin_payment_core_order (
    order_id uuid PRIMARY KEY,
    idempotency_key uuid NOT NULL UNIQUE,
    request_fingerprint char(64) NOT NULL,
    provider_id varchar(32) NOT NULL CHECK (provider_id ~ '^[a-z][a-z0-9-]{0,31}$'),
    settings_version bigint NOT NULL CHECK (settings_version > 0),
    merchant_order_no varchar(32) NOT NULL UNIQUE,
    amount_fen bigint NOT NULL CHECK (amount_fen > 0),
    currency char(3) NOT NULL DEFAULT 'CNY' CHECK (currency = 'CNY'),
    subject varchar(128) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('CREATING','PENDING','UNKNOWN','PAID','CLOSED','FAILED','REFUND_PENDING','REFUNDED')),
    code_url text,
    provider_trade_no varchar(128),
    expires_at timestamptz,
    paid_at timestamptz,
    refund_idempotency_key uuid,
    out_refund_no varchar(32) UNIQUE,
    refund_reason varchar(80),
    refund_status varchar(16) CHECK (refund_status IS NULL OR refund_status IN ('UNKNOWN','PENDING','SUCCESS','FAILED')),
    provider_refund_no varchar(128),
    refund_requested_at timestamptz,
    refunded_at timestamptz,
    last_error_code varchar(48),
    created_by varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((refund_idempotency_key IS NULL) = (out_refund_no IS NULL))
);

CREATE UNIQUE INDEX plugin_payment_core_order_provider_trade_uq
    ON plugin_payment_core_order(provider_id, provider_trade_no)
    WHERE provider_trade_no IS NOT NULL;
CREATE INDEX plugin_payment_core_order_page_idx
    ON plugin_payment_core_order(created_at DESC, order_id DESC);
CREATE INDEX plugin_payment_core_order_reconcile_idx
    ON plugin_payment_core_order(updated_at, provider_id)
    WHERE status IN ('CREATING','PENDING','UNKNOWN','REFUND_PENDING');


-- BEGIN HOST PERMISSION ADOPTION
INSERT INTO platform_authority(user_id, authority)
SELECT u.id, p.authority
  FROM platform_user u
 CROSS JOIN unnest(ARRAY[
       'platform:payment-core:read',
       'platform:payment-core:settings-update',
       'platform:payment-core:order-create',
       'platform:payment-core:order-manage',
       'platform:payment-core:refund'
  ]) p(authority)
 WHERE u.username = 'admin'
   AND u.deleted_at IS NULL
   AND NOT EXISTS (
       SELECT 1
         FROM (VALUES
              ('platform:roles:create'),
              ('platform:roles:update'),
              ('platform:roles:delete'),
              ('platform:roles:assign')) needed(authority)
        WHERE NOT EXISTS (
            SELECT 1 FROM platform_authority a
             WHERE a.user_id = u.id AND a.authority = needed.authority
        )
   )
ON CONFLICT DO NOTHING;

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
