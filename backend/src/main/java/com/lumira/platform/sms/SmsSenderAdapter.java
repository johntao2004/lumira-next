package com.lumira.platform.sms;

import com.lumira.platform.common.SmsSender;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts provider-neutral SMS delivery to the identity verification port. */
@Component
public final class SmsSenderAdapter implements SmsSender {
    private final SmsDeliveryStore queue;
    private final SmsConnectionSource source;

    public SmsSenderAdapter(SmsDeliveryStore queue, SmsConnectionSource source) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public boolean ready() {
        return source.ready();
    }

    @Override
    public String verificationMessage(String code) {
        if (code == null || code.isBlank())
            throw new IllegalArgumentException("Verification code is required");
        try (var connection = source.load()) {
            return connection.settings().template().replace("{code}", code);
        }
    }

    @Override
    public boolean enqueue(UUID operationId, String recipient, String text, int lifetimeSeconds) {
        return queue.enqueue(operationId, new OutgoingSms(recipient, text), lifetimeSeconds);
    }

    @Override
    public boolean enqueueVerification(
            UUID operationId, String recipient, String code, int lifetimeSeconds) {
        try (var connection = source.load()) {
            var settings = connection.settings();
            try {
                SmsProviderProtocol.validateDestination(settings, recipient);
            } catch (IllegalArgumentException mismatch) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "smsProviderRejected");
            }
            String text =
                    settings.template()
                            .replace("{code}", code)
                            .replace(
                                    "{minutes}",
                                    Integer.toString(Math.max(1, (lifetimeSeconds + 59) / 60)));
            return queue.enqueue(
                    operationId,
                    new OutgoingSms(
                            recipient,
                            text,
                            code,
                            lifetimeSeconds,
                            SmsProviderProtocol.configurationKey(settings)),
                    lifetimeSeconds);
        }
    }
}
