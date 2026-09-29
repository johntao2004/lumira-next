package com.lumira.platform.common;

import java.util.Arrays;
import java.util.Objects;

/** Kernel contract for one short-lived SMS provider configuration. */
public interface SmsConfigurationPort {
    /** Reads non-secret SMS settings without revealing the stored provider secret. */
    Settings readSettings();

    Configuration load();

    record Settings(boolean enabled, String provider, String endpoint, String sender,
                    String template, int timeoutSeconds) {}

    record Configuration(Settings settings, byte[] secret) {
        public Configuration {
            Objects.requireNonNull(settings, "sms settings");
        }

        public void clear() {
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
    }
}
