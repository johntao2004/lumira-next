-- V1 is published and maps legacy shared payment grants to this provider's own pages/actions.
-- Run before the host retires obsolete shared authorities on fresh or upgraded installations.
DO $$
BEGIN
    IF to_regprocedure('public.platform_normalize_legacy_permission_projections()') IS NULL THEN
        RAISE EXCEPTION 'alipay-pay permission finalization requires platform migration V51';
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
