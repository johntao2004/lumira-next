package com.lumira.platform.events;

/**
 * Legacy durable Outbox consumer registration retained for compatibility.
 *
 * <p>The worker adapts this interface to {@code DurableEventConsumerPort} and runs it in the same
 * database transaction as the delivery acknowledgement. Handlers must restrict side effects to that
 * transaction's database. They must not perform network calls or other external effects; external
 * delivery requires its own durable outbox because a database rollback cannot undo it.
 */
public interface PlatformEventConsumer {
    String sourceType();

    String eventType();

    void consume(PlatformEventOutboxStore.Event event) throws Exception;
}
