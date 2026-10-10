package com.lumira.platform.identity.api;

import java.time.Instant;
import java.util.UUID;

/** Host-owned, one-use recovery tickets shared with the authenticator plugin. */
public interface MfaRecoveryTicketPort {
    record Issued(String value, Instant expiresAt) {}

    enum ConsumeResult {
        CONSUMED,
        INVALID,
        LOCKED
    }

    /** Issue inside the credential-change transaction after RECOVERY_PENDING is established. */
    Issued issue(UUID userId);

    /** Consume only while the same account/factor revision remains recovery-pending. */
    ConsumeResult consume(UUID userId, String value);

    void revoke(UUID userId);
}
