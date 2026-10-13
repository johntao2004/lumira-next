package com.lumira.platform.events;

import com.lumira.platform.common.DurableEventConsumerPort;
import com.lumira.platform.common.DurableEventProducerPort;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Validates the event handlers supplied by loaded modules and resolves one deterministic handler
 * for each event type. No class name or code comes from the database, so navigation and event
 * payloads cannot load arbitrary code.
 */
@Component
public final class PlatformEventConsumerRegistry {
    private record Key(String sourceType, String eventType) {}

    private final Map<Key, DurableEventConsumerPort> consumers;

    /** Compatibility constructor for callers that still register legacy handlers. */
    public PlatformEventConsumerRegistry(List<PlatformEventConsumer> candidates) {
        this(List.of(), candidates);
    }

    @Autowired
    public PlatformEventConsumerRegistry(
            List<DurableEventConsumerPort> durableCandidates,
            List<PlatformEventConsumer> legacyCandidates) {
        var indexed = new LinkedHashMap<Key, DurableEventConsumerPort>();
        for (var candidate :
                durableCandidates == null
                        ? List.<DurableEventConsumerPort>of()
                        : durableCandidates) {
            if (candidate == null
                    || !validSource(candidate.sourceType())
                    || !validEvent(candidate.eventType())) {
                throw new IllegalArgumentException("Invalid event consumer registration");
            }
            var key = new Key(candidate.sourceType(), candidate.eventType());
            if (indexed.putIfAbsent(key, candidate) != null) {
                throw new IllegalArgumentException(
                        "Duplicate event consumer: "
                                + candidate.sourceType()
                                + "/"
                                + candidate.eventType());
            }
        }
        for (var candidate :
                legacyCandidates == null ? List.<PlatformEventConsumer>of() : legacyCandidates) {
            if (candidate == null
                    || !validSource(candidate.sourceType())
                    || !validEvent(candidate.eventType())) {
                throw new IllegalArgumentException("Invalid event consumer registration");
            }
            var key = new Key(candidate.sourceType(), candidate.eventType());
            var adapter = new LegacyConsumerAdapter(candidate);
            if (indexed.putIfAbsent(key, adapter) != null) {
                throw new IllegalArgumentException(
                        "Duplicate event consumer: "
                                + candidate.sourceType()
                                + "/"
                                + candidate.eventType());
            }
        }
        consumers = Map.copyOf(indexed);
    }

    /** Preserves the original lookup API for code that still consumes the platform event shape. */
    public Optional<PlatformEventConsumer> resolve(String sourceType, String eventType) {
        var consumer = consumers.get(new Key(sourceType, eventType));
        if (consumer == null) return Optional.empty();
        if (consumer instanceof LegacyConsumerAdapter legacy) return Optional.of(legacy.delegate());
        return Optional.of(new CommonConsumerAdapter(consumer));
    }

    public Optional<DurableEventConsumerPort> resolveConsumer(String sourceType, String eventType) {
        return Optional.ofNullable(consumers.get(new Key(sourceType, eventType)));
    }

    public boolean isEmpty() {
        return consumers.isEmpty();
    }

    public int size() {
        return consumers.size();
    }

    public Set<DurableEventProducerPort.EventType> eventTypes() {
        var types = new LinkedHashSet<DurableEventProducerPort.EventType>();
        consumers.keySet().stream()
                .sorted(Comparator.comparing(Key::sourceType).thenComparing(Key::eventType))
                .forEach(
                        key ->
                                types.add(
                                        new DurableEventProducerPort.EventType(
                                                key.sourceType(), key.eventType())));
        return Collections.unmodifiableSet(types);
    }

    private record LegacyConsumerAdapter(PlatformEventConsumer delegate)
            implements DurableEventConsumerPort {
        @Override
        public String sourceType() {
            return delegate.sourceType();
        }

        @Override
        public String eventType() {
            return delegate.eventType();
        }

        @Override
        public void consume(DurableEventConsumerPort.Event event) throws Exception {
            delegate.consume(
                    new PlatformEventOutboxStore.Event(
                            event.id(),
                            event.sourceType(),
                            event.eventType(),
                            event.eventKey(),
                            event.payload(),
                            event.attempts()));
        }
    }

    private record CommonConsumerAdapter(DurableEventConsumerPort delegate)
            implements PlatformEventConsumer {
        @Override
        public String sourceType() {
            return delegate.sourceType();
        }

        @Override
        public String eventType() {
            return delegate.eventType();
        }

        @Override
        public void consume(PlatformEventOutboxStore.Event event) throws Exception {
            delegate.consume(
                    new DurableEventConsumerPort.Event(
                            event.id(),
                            event.sourceType(),
                            event.eventType(),
                            event.eventKey(),
                            event.payload(),
                            event.attempts()));
        }
    }

    private static boolean validSource(String value) {
        return value != null && value.matches("[a-z][a-z0-9._:-]{0,63}");
    }

    private static boolean validEvent(String value) {
        return value != null && value.matches("[a-z][a-z0-9._:-]{0,127}");
    }
}
