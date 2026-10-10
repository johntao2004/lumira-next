package com.lumira.platform.identity.api;

/** Settings-owned WeChat configuration projection consumed by the identity module. */
public interface WechatConfigurationPort extends WechatAvailabilityPort {
    /** Non-secret snapshot. Credentials are never included in this value. */
    record Snapshot(
            long version,
            boolean enabled,
            String appId,
            String publicOrigin,
            int timeoutSeconds,
            boolean appSecretConfigured,
            boolean ready) {}

    /** Short-lived provider payload. Implementations must zero secret bytes on close. */
    interface ProviderConfiguration extends AutoCloseable {
        long version();

        String appId();

        String publicOrigin();

        int timeoutSeconds();

        byte[] secret();

        @Override
        void close();
    }

    Snapshot snapshot();

    ProviderConfiguration providerConfiguration();
}
