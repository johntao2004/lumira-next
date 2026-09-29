package com.lumira.platform.identity.api;

import java.util.List;

/** Stable WebAuthn policy contract; persistence and administration stay outside identity. */
public interface PasskeyPolicyPort {
    Policy currentPolicy();

    record Policy(
            Boolean enabled,
            Boolean passwordlessEnabled,
            Boolean selfBindingEnabled,
            String rpId,
            String rpName,
            List<String> allowedOrigins,
            Integer challengeTtlSeconds) {
        public Policy {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }
}
