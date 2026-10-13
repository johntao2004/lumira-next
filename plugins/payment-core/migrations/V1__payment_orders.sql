CREATE TABLE plugin_payment_core_settings (
    provider_id varchar(32) PRIMARY KEY CHECK (provider_id IN ('wechat-pay','alipay-pay')),
    current_version bigint NOT NULL CHECK (current_version >= 0),
    values_json text NOT NULL,
    updated_by varchar(100) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE plugin_payment_core_settings_history (
    provider_id varchar(32) NOT NULL CHECK (provider_id IN ('wechat-pay','alipay-pay')),
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
    provider_id varchar(32) NOT NULL CHECK (provider_id IN ('wechat-pay','alipay-pay')),
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

INSERT INTO platform_authority(user_id, authority)
SELECT u.id, p.authority
  FROM platform_user u
 CROSS JOIN unnest(ARRAY[
       'platform:payment-core:read',
       'platform:payment-core:manage',
       'platform:payment-core:create',
       'platform:payment-core:refund'
  ]) p(authority)
 WHERE u.username = 'admin'
   AND u.deleted_at IS NULL
   AND EXISTS (
       SELECT 1 FROM platform_authority a
        WHERE a.user_id = u.id AND a.authority = 'platform:roles:write'
   )
ON CONFLICT DO NOTHING;
