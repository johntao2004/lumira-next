package com.lumira.platform.common;

import java.util.List;

/** Declares event types emitted by a producer so the platform can report unconnected contracts. */
public interface DurableEventProducerPort {
    record EventType(String sourceType, String eventType) {
        public EventType {
            if (sourceType == null || !sourceType.matches("[a-z][a-z0-9._:-]{0,63}")) {
                throw new IllegalArgumentException("Invalid event source");
            }
            if (eventType == null || !eventType.matches("[a-z][a-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException("Invalid event type");
            }
        }
    }

    List<EventType> eventTypes();
}
