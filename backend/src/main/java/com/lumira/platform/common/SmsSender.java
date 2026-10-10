package com.lumira.platform.common;

import java.util.UUID;

/** Identity-facing SMS contract; provider credentials stay inside communication. */
public interface SmsSender {
    /** Non-throwing readiness check; persistence and infrastructure failures propagate. */
    default boolean ready() {
        return false;
    }

    String verificationMessage(String code);

    boolean enqueue(UUID operationId, String recipient, String text, int lifetimeSeconds);

    default boolean enqueueVerification(
            UUID operationId, String recipient, String code, int lifetimeSeconds) {
        return enqueue(operationId, recipient, verificationMessage(code), lifetimeSeconds);
    }
}
