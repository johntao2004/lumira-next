package com.lumira.platform.identity.api;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

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
        private static final Pattern RP_ID =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,252}");

        public Policy {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }

        /** True only when the full persisted policy is valid for WebAuthn operations. */
        public boolean validConfiguration() {
            if (enabled == null
                    || passwordlessEnabled == null
                    || selfBindingEnabled == null
                    || rpId == null
                    || rpName == null
                    || allowedOrigins.isEmpty()
                    || challengeTtlSeconds == null) return false;

            String normalizedRpId = rpId.strip().toLowerCase(Locale.ROOT);
            String normalizedRpName = rpName.strip();
            if (normalizedRpId.isBlank()
                    || normalizedRpId.length() > 253
                    || !RP_ID.matcher(normalizedRpId).matches()
                    || normalizedRpId.startsWith(".")
                    || normalizedRpId.endsWith(".")
                    || normalizedRpId.contains("..")) return false;
            if (normalizedRpName.isBlank()
                    || normalizedRpName.length() > 255
                    || normalizedRpName.codePoints().anyMatch(Character::isISOControl)) return false;
            if (allowedOrigins.size() > 32) return false;
            for (String origin : allowedOrigins) {
                if (!validOrigin(origin)) return false;
            }
            if (challengeTtlSeconds < 30 || challengeTtlSeconds > 600) return false;
            return enabled || (!passwordlessEnabled && !selfBindingEnabled);
        }

        /** True only when login can use a complete and valid passwordless policy. */
        public boolean passwordlessReady() {
            return validConfiguration()
                    && Boolean.TRUE.equals(enabled)
                    && Boolean.TRUE.equals(passwordlessEnabled);
        }

        private static boolean validOrigin(String origin) {
            if (origin == null
                    || origin.isBlank()
                    || origin.length() > 512
                    || origin.codePoints().anyMatch(Character::isISOControl)) return false;
            try {
                URI uri = URI.create(origin.strip());
                return ("http".equalsIgnoreCase(uri.getScheme())
                                || "https".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null
                        && uri.getUserInfo() == null
                        && uri.getQuery() == null
                        && uri.getFragment() == null
                        && (uri.getPath() == null
                                || uri.getPath().isEmpty()
                                || "/".equals(uri.getPath()));
            } catch (IllegalArgumentException invalidOrigin) {
                return false;
            }
        }
    }
}
