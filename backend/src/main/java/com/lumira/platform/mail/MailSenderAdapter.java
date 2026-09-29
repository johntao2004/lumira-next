package com.lumira.platform.mail;

import com.lumira.platform.common.MailSender;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the communication module's SMTP queue to the identity port. */
@Component
public final class MailSenderAdapter implements MailSender {
    private final MailDeliveryStore queue;
    private final SmtpConnectionSource source;

    public MailSenderAdapter(MailDeliveryStore queue, SmtpConnectionSource source) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public void validateRecipient(String recipient) {
        new OutgoingMail(recipient, "Validation", "");
    }

    @Override
    public void ensureAvailable() {
        try (var ignored = source.load()) {
            // Loading the connection validates the current non-secret and secret configuration.
        }
    }

    @Override
    public boolean enqueue(UUID operationId, String recipient, String subject, String text, int lifetimeSeconds) {
        return queue.enqueue(operationId, new OutgoingMail(recipient, subject, text), lifetimeSeconds);
    }
}
