package com.lumira.platform.settings;

import com.lumira.platform.identity.api.PasskeyPolicyPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    public static PasskeySettings defaults() {
        return new PasskeySettings(
                false, false, false, "127.0.0.1", "Lumira", List.of("http://127.0.0.1:8010"), 120);
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
        if (!toPolicy(value).validConfiguration()) invalid();
    }

    private static PasskeyPolicyPort.Policy toPolicy(PasskeySettings value) {
        return new PasskeyPolicyPort.Policy(
                value.enabled,
                value.passwordlessEnabled,
                value.selfBindingEnabled,
                value.rpId,
                value.rpName,
                value.allowedOrigins,
                value.challengeTtlSeconds);
    }

    private static void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidPasskeySettings");
    }
}
