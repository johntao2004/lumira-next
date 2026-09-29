package com.lumira.platform.settings;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Runtime WebAuthn relying-party policy. Secrets and credentials are stored elsewhere. */
public record PasskeySettings(
        Boolean enabled,
        Boolean passwordlessEnabled,
        Boolean selfBindingEnabled,
        String rpId,
        String rpName,
        List<String> allowedOrigins,
        Integer challengeTtlSeconds) {

    private static final Pattern RP_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,252}");

    public static PasskeySettings defaults() {
        return new PasskeySettings(
                false,
                false,
                false,
                "127.0.0.1",
                "Lumira",
                List.of("http://127.0.0.1:8010"),
                120);
    }

    public PasskeySettings normalized() {
        String normalizedRpId = rpId == null ? "" : rpId.strip().toLowerCase(Locale.ROOT);
        String normalizedRpName = rpName == null ? "" : rpName.strip();
        List<String> normalizedOrigins = new ArrayList<>();
        if (allowedOrigins != null) {
            for (String origin : allowedOrigins) {
                if (origin != null && !origin.isBlank()) {
                    String value = origin.strip();
                    if (!normalizedOrigins.contains(value)) normalizedOrigins.add(value);
                }
            }
        }
        return new PasskeySettings(
                enabled,
                passwordlessEnabled,
                selfBindingEnabled,
                normalizedRpId,
                normalizedRpName,
                List.copyOf(normalizedOrigins),
                challengeTtlSeconds);
    }

    public void validate() {
        PasskeySettings value = normalized();
        if (value.enabled == null || value.passwordlessEnabled == null || value.selfBindingEnabled == null
                || value.rpId == null || value.rpId.isBlank() || value.rpName == null
                || value.rpName.isBlank() || value.allowedOrigins == null || value.allowedOrigins.isEmpty()
                || value.challengeTtlSeconds == null) {
            invalid();
        }
        if (value.rpId.length() > 253 || !RP_ID.matcher(value.rpId).matches()
                || value.rpId.startsWith(".") || value.rpId.endsWith(".") || value.rpId.contains("..")) {
            invalid();
        }
        if (value.rpName.length() > 255 || value.rpName.codePoints().anyMatch(Character::isISOControl)) {
            invalid();
        }
        if (value.allowedOrigins.size() > 32) invalid();
        for (String origin : value.allowedOrigins) validateOrigin(origin);
        if (value.challengeTtlSeconds < 30 || value.challengeTtlSeconds > 600) invalid();
        if (!value.enabled && (value.passwordlessEnabled || value.selfBindingEnabled)) invalid();
    }

    private static void validateOrigin(String origin) {
        if (origin == null || origin.length() > 512 || origin.codePoints().anyMatch(Character::isISOControl)) invalid();
        try {
            URI uri = URI.create(origin);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                invalid();
            }
        } catch (IllegalArgumentException invalidOrigin) {
            invalid();
        }
    }

    private static void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidPasskeySettings");
    }
}
