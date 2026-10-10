package com.lumira.platform.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Pending responses never carry an authenticated session. */
public record LoginResponse(
        boolean authenticated,
        boolean secondFactorRequired,
        UUID challengeId,
        Instant expiresAt,
        String state,
        String policyRevision,
        List<String> factors,
        String nextPath) {
    public LoginResponse(
            boolean authenticated,
            boolean secondFactorRequired,
            UUID challengeId,
            Instant expiresAt) {
        this(
                authenticated,
                secondFactorRequired,
                challengeId,
                expiresAt,
                authenticated ? "COMPLETE" : secondFactorRequired ? "MFA_REQUIRED" : "NONE",
                null,
                List.of(),
                null);
    }

    public LoginResponse {
        factors = factors == null ? List.of() : List.copyOf(factors);
    }

    public static LoginResponse complete() {
        return new LoginResponse(true, false, null, null, "COMPLETE", null, List.of(), null);
    }
}
