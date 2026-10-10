package com.lumira.platform.events;

/**
 * A durable Outbox consumer owned by a platform or business module.
 *
 * <p>Consumers run outside the database transaction that claims an event. The worker acknowledges
 * the claim only after this method returns successfully; an implementation must therefore be
 * idempotent for at-least-once delivery.
 */
public interface PlatformEventConsumer {
    String sourceType();

    String eventType();

    void consume(PlatformEventOutboxStore.Event event) throws Exception;
}
