package com.lumira.platform.common;

import java.util.Arrays;
import java.util.Objects;

/**
 * Kernel contract for opening one short-lived mail transport configuration. The returned credential
 * is owned by the configuration and must be cleared by the communication adapter after the attempt.
 */
public interface MailConfigurationPort {
    /** Reads non-secret SMTP settings without revealing the stored password. */
    Settings readSettings();

    /** Checks expected configuration readiness without exception-based control flow. */
    default boolean ready() {
        return false;
    }

    Configuration load();

    record Settings(
            boolean enabled,
            String host,
            int port,
            String security,
            boolean authentication,
            String username,
            String fromAddress,
            String fromName) {}

    record Configuration(Settings settings, byte[] password) {
        public Configuration {
            Objects.requireNonNull(settings, "mail settings");
        }

        public void clear() {
            if (password != null) Arrays.fill(password, (byte) 0);
        }
    }
}
