package com.lumira.platform.common;

import java.util.UUID;

/**
 * Identity-facing mail contract. The port deliberately contains no SMTP, queue entity, or Spring
 * MVC type so the communication implementation can be replaced without changing identity use cases.
 */
public interface MailSender {
    void validateRecipient(String recipient);

    /** Non-throwing readiness check; persistence and infrastructure failures propagate. */
    default boolean ready() {
        return false;
    }

    void ensureAvailable();

    boolean enqueue(
            UUID operationId, String recipient, String subject, String text, int lifetimeSeconds);
}
