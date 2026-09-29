package com.lumira.platform.events;

import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Validates the event handlers supplied by loaded modules and resolves one
 * deterministic handler for each event type. No class name or code comes from
 * the database, so navigation and event payloads cannot load arbitrary code.
 */
@Component
public final class PlatformEventConsumerRegistry {
    private record Key(String sourceType, String eventType) {}

    private final Map<Key, PlatformEventConsumer> consumers;

    public PlatformEventConsumerRegistry(List<PlatformEventConsumer> candidates) {
        var indexed = new LinkedHashMap<Key, PlatformEventConsumer>();
        for (var candidate : candidates == null ? List.<PlatformEventConsumer>of() : candidates) {
            if (candidate == null || !validSource(candidate.sourceType()) || !validEvent(candidate.eventType())) {
                throw new IllegalArgumentException("Invalid event consumer registration");
            }
            var key = new Key(candidate.sourceType(), candidate.eventType());
            if (indexed.putIfAbsent(key, candidate) != null) {
                throw new IllegalArgumentException("Duplicate event consumer: " + candidate.sourceType() + "/" + candidate.eventType());
            }
        }
        consumers = Map.copyOf(indexed);
    }

    public Optional<PlatformEventConsumer> resolve(String sourceType, String eventType) {
        return Optional.ofNullable(consumers.get(new Key(sourceType, eventType)));
    }

    public boolean isEmpty() {
        return consumers.isEmpty();
    }

    public int size() {
        return consumers.size();
    }

    private static boolean validSource(String value) {
        return value != null && value.matches("[a-z][a-z0-9._:-]{0,63}");
    }

    private static boolean validEvent(String value) {
        return value != null && value.matches("[a-z][a-z0-9._:-]{0,127}");
    }
}
