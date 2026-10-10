-- Business rejection is a terminal result, distinct from authentication and transport failures.
ALTER TABLE platform_sms_delivery
    DROP CONSTRAINT platform_sms_delivery_last_result_check;
ALTER TABLE platform_sms_delivery
    ADD CONSTRAINT platform_sms_delivery_last_result_check CHECK (last_result IN (
        'ACCEPTED', 'AUTHENTICATION_FAILED', 'PROVIDER_REJECTED', 'CONNECTION_FAILED',
        'DELIVERY_UNCERTAIN', 'CONFIGURATION_UNAVAILABLE', 'LEASE_EXPIRED'
    ));
