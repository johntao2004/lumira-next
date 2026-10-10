package com.lumira.platform.identity.api;

import java.util.UUID;

/** Trusted account creation capability for the identity registration transaction only. */
public interface AccountProvisioningPort {
    PreparedPassword preparePassword(String rawPassword);

    UUID provision(String username, String displayName, PreparedPassword password);

    record PreparedPassword(String encodedValue) {
        public PreparedPassword {
            if (encodedValue == null || encodedValue.isBlank())
                throw new IllegalArgumentException("invalidPreparedPassword");
        }

        @Override
        public String toString() {
            return "PreparedPassword[redacted]";
        }
    }
}
