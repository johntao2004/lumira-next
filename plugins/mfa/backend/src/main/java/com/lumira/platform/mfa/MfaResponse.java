package com.lumira.platform.mfa;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MfaResponse {
    private MfaResponse() {}

    public record Status(
            boolean enabled, String label, Instant createdAt, int recoveryCodesRemaining) {}

    public record Setup(UUID challengeId, String secret, String uri, Instant expiresAt) {}

    public record Recovery(List<String> recoveryCodes) {}

    public record Account(
            UUID id,
            String username,
            String displayName,
            String label,
            Instant createdAt,
            int recoveryCodesRemaining) {}
}
