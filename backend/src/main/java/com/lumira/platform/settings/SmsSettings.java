package com.lumira.platform.settings;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Non-secret, provider-neutral SMS webhook settings. The credential is kept in platform_secret. */
public record SmsSettings(Boolean enabled, String provider, String endpoint, String sender,
                          String template, Integer timeoutSeconds) {
    public static SmsSettings defaults() {
        return new SmsSettings(false, "webhook", "", "Lumira", "Lumira verification code: {code}", 10);
    }

    public void validate() {
        if (enabled == null || provider == null || !provider.equals("webhook")
                || endpoint == null || sender == null || template == null || timeoutSeconds == null
                || timeoutSeconds < 1 || timeoutSeconds > 30
                || sender.isBlank() || sender.length() > 64
                || template.isBlank() || template.length() > 500
                || sender.codePoints().anyMatch(Character::isISOControl)
                || template.codePoints().anyMatch(Character::isISOControl)
                || !template.contains("{code}") || template.contains("{phone}")) {
            invalid();
        }
        if (endpoint.isBlank()) {
            if (enabled) invalid();
            return;
        }
        if (endpoint.length() > 2000 || endpoint.codePoints().anyMatch(Character::isISOControl)
                || endpoint.contains("\\")) invalid();
        try {
            URI uri = URI.create(endpoint);
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            boolean local = uri.getHost() != null && (uri.getHost().equalsIgnoreCase("localhost")
                    || uri.getHost().equals("127.0.0.1") || uri.getHost().equals("[::1]")
                    || uri.getHost().equals("::1"));
            if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getHost() == null || (!secure && !local) || (enabled && !secure && !local)) invalid();
        } catch (IllegalArgumentException ex) {
            invalid();
        }
    }

    public boolean sameCredentialDestination(SmsSettings other) {
        return other != null && provider.equals(other.provider) && endpoint.equalsIgnoreCase(other.endpoint);
    }

    private static void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidSms");
    }
}
