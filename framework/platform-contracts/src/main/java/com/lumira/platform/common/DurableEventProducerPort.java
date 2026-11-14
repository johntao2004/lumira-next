package com.lumira.platform.common;

import java.util.List;

/**
 * 声明生产事件类型，便于平台发现未连接的契约。 Declares event types emitted by a producer so the platform can report
 * unconnected contracts.
 */
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

    /** 返回本生产者声明的来源与事件类型。 Returns source and event types declared by this producer. */
    List<EventType> eventTypes();
}
