package com.lumira.platform.events;

import com.lumira.platform.common.DurableEventConsumerPort;
import com.lumira.platform.common.DurableEventProducerPort;
import com.lumira.platform.common.DynamicEventConsumerPort;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Validates the event handlers supplied by loaded modules and resolves one deterministic handler
 * for each event type. No class name or code comes from the database, so navigation and event
 * payloads cannot load arbitrary code.
 */
@Component
public final class PlatformEventConsumerRegistry implements DynamicEventConsumerPort {
    private record Key(String sourceType, String eventType) {}

    private final Map<Key, DurableEventConsumerPort> consumers;

    private record Registration(
            UUID identity, DurableEventConsumerPort consumer, Supplier<AutoCloseable> admission) {}

    private volatile Map<Key, Registration> dynamic = Map.of();
    private volatile Map<UUID, Set<DurableEventProducerPort.EventType>> producerDeclarations =
            Map.of();

    @Override
    public synchronized AutoCloseable registerProducers(
            String owner, Collection<DurableEventProducerPort> candidates) {
        if (owner == null || owner.isBlank())
            throw new IllegalArgumentException("Invalid producer owner");
        var types = new HashSet<DurableEventProducerPort.EventType>();
        for (var candidate : candidates) types.addAll(candidate.eventTypes());
        var token = UUID.randomUUID();
        var next = new HashMap<>(producerDeclarations);
        next.put(token, Set.copyOf(types));
        producerDeclarations = Map.copyOf(next);
        return () -> {
            synchronized (this) {
                var remaining = new HashMap<>(producerDeclarations);
                remaining.remove(token);
                producerDeclarations = Map.copyOf(remaining);
            }
        };
    }

    public Set<DurableEventProducerPort.EventType> producedEventTypes() {
        var result = new HashSet<DurableEventProducerPort.EventType>();
        producerDeclarations.values().forEach(result::addAll);
        return Set.copyOf(result);
    }

    public record Lease(DurableEventConsumerPort consumer, AutoCloseable admission)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            admission.close();
        }
    }

    @Override
    public synchronized AutoCloseable registerConsumers(
            String owner,
            Collection<DurableEventConsumerPort> candidates,
            Supplier<AutoCloseable> admission) {
        if (owner == null || owner.isBlank() || admission == null)
            throw new IllegalArgumentException("Invalid consumer owner");
        var added = new LinkedHashMap<Key, Registration>();
        for (var candidate : candidates) {
            if (candidate == null
                    || !validSource(candidate.sourceType())
                    || !validEvent(candidate.eventType()))
                throw new IllegalArgumentException("Invalid event consumer registration");
            var key = new Key(candidate.sourceType(), candidate.eventType());
            if (consumers.containsKey(key) || dynamic.containsKey(key) || added.containsKey(key))
                throw new IllegalArgumentException("Duplicate event consumer: " + key);
            added.put(key, new Registration(UUID.randomUUID(), candidate, admission));
        }
        var next = new LinkedHashMap<>(dynamic);
        next.putAll(added);
        dynamic = Map.copyOf(next);
        return () -> {
            synchronized (this) {
                var remaining = new LinkedHashMap<>(dynamic);
                added.forEach((key, registration) -> remaining.remove(key, registration));
                dynamic = Map.copyOf(remaining);
            }
        };
    }

    public Optional<Lease> acquire(String sourceType, String eventType) {
        var key = new Key(sourceType, eventType);
        var fixed = consumers.get(key);
        if (fixed != null) return Optional.of(new Lease(fixed, () -> {}));
        var registration = dynamic.get(key);
        if (registration == null) return Optional.empty();
        var admission = registration.admission().get();
        if (admission != null && dynamic.get(key) != registration) {
            try {
                admission.close();
            } catch (Exception error) {
                throw new IllegalStateException("Consumer admission release failed", error);
            }
            return Optional.empty();
        }
        return admission == null
                ? Optional.empty()
                : Optional.of(new Lease(registration.consumer(), admission));
    }

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
        return consumers.isEmpty() && dynamic.isEmpty();
    }

    public int size() {
        return consumers.size() + dynamic.size();
    }

    public Set<DurableEventProducerPort.EventType> eventTypes() {
        var types = new LinkedHashSet<DurableEventProducerPort.EventType>();
        var keys = new HashSet<>(consumers.keySet());
        keys.addAll(dynamic.keySet());
        keys.stream()
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
