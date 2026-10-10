package com.lumira.platform.common;

import java.util.Arrays;
import java.util.Objects;

/** Kernel contract for one short-lived SMS provider configuration. */
public interface SmsConfigurationPort {
    /** Reads non-secret SMS settings without revealing the stored provider secret. */
    Settings readSettings();

    /** Checks expected configuration readiness without exception-based control flow. */
    default boolean ready() {
        return false;
    }

    Configuration load();

    record Settings(
            boolean enabled,
            String provider,
            String endpoint,
            String sender,
            String template,
            int timeoutSeconds,
            String market,
            String region,
            String accessKeyId,
            String appId,
            String templateId,
            String templateParams,
            String senderId,
            String signature) {
        public Settings(
                boolean enabled,
                String provider,
                String endpoint,
                String sender,
                String template,
                int timeoutSeconds) {
            this(
                    enabled,
                    provider,
                    endpoint,
                    sender,
                    template,
                    timeoutSeconds,
                    "domestic",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "");
        }
    }

    record Configuration(Settings settings, byte[] secret) {
        public Configuration {
            Objects.requireNonNull(settings, "sms settings");
        }

        public void clear() {
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
    }
}
