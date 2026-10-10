package com.lumira.platform.events;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformEventConsumerRegistryTest {
    private static PlatformEventConsumer consumer(String source, String event) {
        return new PlatformEventConsumer() {
            @Override
            public String sourceType() {
                return source;
            }

            @Override
            public String eventType() {
                return event;
            }

            @Override
            public void consume(PlatformEventOutboxStore.Event ignored) {}
        };
    }

    @Test
    void resolvesExactHandlersAndKeepsEmptyRegistrySafe() {
        var handler = consumer("feedback", "feedback.created");
        var registry = new PlatformEventConsumerRegistry(List.of(handler));
        assertEquals(1, registry.size());
        assertSame(handler, registry.resolve("feedback", "feedback.created").orElseThrow());
        assertTrue(registry.resolve("feedback", "feedback.updated").isEmpty());
        assertFalse(registry.isEmpty());
        assertTrue(new PlatformEventConsumerRegistry(List.of()).isEmpty());
    }

    @Test
    void rejectsMalformedAndDuplicateRegistrations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlatformEventConsumerRegistry(List.of(consumer("Bad", "event"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlatformEventConsumerRegistry(List.of(consumer("source", "Bad"))));
        var handler = consumer("source", "event");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PlatformEventConsumerRegistry(
                                List.of(handler, consumer("source", "event"))));
    }
}
