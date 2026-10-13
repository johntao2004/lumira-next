package com.lumira.platform.identity.api;

import java.time.Instant;
import java.util.UUID;

/** Identity capability used by the directory module's administrative user endpoint. */
public interface TemporaryPasswordResetPort {
    ResetResult reset(UUID userId, Long expectedVersion, String actorId);

    record ResetResult(String temporaryPassword, Instant expiresAt) {
        @Override
        public String toString() {
            return "ResetResult[temporaryPassword=redacted, expiresAt=" + expiresAt + "]";
        }
    }
}
