package com.lumira.platform.identity.api;

import java.util.List;

/** Server-side, non-secret evidence recorded after final authentication. */
public final class AuthenticationSession {
    public static final String PRIMARY_METHOD = "lumira.identity.primary-method";
    public static final String VERIFIED_FACTORS = "lumira.identity.verified-factors";
    public static final String AUTHENTICATED_AT = "lumira.identity.authenticated-at";
    public static final String AUTHENTICATION_REVISION = "lumira.identity.authentication-revision";
    public static final String PRIMARY_CREDENTIAL_REVISION =
            "lumira.identity.primary-credential-revision";
    public static final String FACTOR_CREDENTIAL_REVISION =
            "lumira.identity.factor-credential-revision";

    private AuthenticationSession() {}

    public static List<String> factors(Object value) {
        if (value instanceof List<?> values)
            return values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        return List.of();
    }
}
