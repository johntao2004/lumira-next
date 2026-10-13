package com.lumira.platform.common;

import java.util.UUID;

/** Writes a durable event in the caller's business transaction. */
public interface DurableEventPublisherPort {
    boolean publish(UUID id, String sourceType, String eventType, String eventKey, String payload);
}
