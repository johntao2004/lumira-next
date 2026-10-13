package com.lumira.platform.common;

import java.util.UUID;

/**
 * Handles one event using database effects that participate in the worker's acknowledgement
 * transaction. Implementations must avoid network and other external effects; those require a
 * separate durable outbox and are not covered by this transaction.
 */
public interface DurableEventConsumerPort {
    record Event(
            UUID id,
            String sourceType,
            String eventType,
            String eventKey,
            String payload,
            int attempts) {}

    String sourceType();

    String eventType();

    void consume(Event event) throws Exception;
}
