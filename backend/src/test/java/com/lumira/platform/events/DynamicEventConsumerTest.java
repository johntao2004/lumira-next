package com.lumira.platform.events;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.DurableEventConsumerPort;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DynamicEventConsumerTest {
    @Test
    void producerTopologyTracksOwnedRegistration() throws Exception {
        var registry = new PlatformEventConsumerRegistry(List.of());
        var topology = new PlatformEventTopology(List.of(), registry);
        var type = new com.lumira.platform.common.DurableEventProducerPort.EventType("plugin", "a");
        try (var registration = registry.registerProducers("probe", List.of(() -> List.of(type)))) {
            assertEquals(java.util.Set.of(type), registry.producedEventTypes());
        }
        assertTrue(registry.producedEventTypes().isEmpty());
    }

    @Test
    void leaseIsHeldThroughAcknowledgement() throws Exception {
        var store = org.mockito.Mockito.mock(PlatformEventOutboxStore.class);
        var registry = new PlatformEventConsumerRegistry(List.of());
        var active = new AtomicInteger();
        var claim =
                new PlatformEventOutboxStore.Claim(
                        java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 1);
        org.mockito.Mockito.when(store.event(claim))
                .thenReturn(
                        java.util.Optional.of(
                                new PlatformEventOutboxStore.Event(
                                        claim.id(), "plugin", "a", "key", "{}", 1)));
        org.mockito.Mockito.when(
                        store.deliver(
                                org.mockito.ArgumentMatchers.eq(claim),
                                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        invocation -> {
                            assertEquals(1, active.get());
                            return true;
                        });
        try (var registration =
                        registry.registerConsumers(
                                "probe",
                                List.of(consumer("a")),
                                () -> {
                                    active.incrementAndGet();
                                    return () -> active.decrementAndGet();
                                });
                var worker = new PlatformEventOutboxWorker(store, registry)) {
            worker.process(claim);
            assertEquals(0, active.get());
            org.mockito.Mockito.verify(store)
                    .deliver(
                            org.mockito.ArgumentMatchers.eq(claim),
                            org.mockito.ArgumentMatchers.any());
        }
    }

    private DurableEventConsumerPort consumer(String type) {
        return new DurableEventConsumerPort() {
            public String sourceType() {
                return "plugin";
            }

            public String eventType() {
                return type;
            }

            public void consume(Event event) {}
        };
    }

    @Test
    void registrationIsAtomicOwnedAndReinstallable() throws Exception {
        var registry = new PlatformEventConsumerRegistry(List.of());
        var active = new AtomicInteger();
        var registration =
                registry.registerConsumers(
                        "probe",
                        List.of(consumer("a")),
                        () -> {
                            active.incrementAndGet();
                            return () -> active.decrementAndGet();
                        });
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        registry.registerConsumers(
                                "other", List.of(consumer("b"), consumer("a")), () -> () -> {}));
        assertEquals(1, registry.size());
        try (var lease = registry.acquire("plugin", "a").orElseThrow()) {
            assertEquals(1, active.get());
            registration.close();
            assertTrue(registry.acquire("plugin", "a").isEmpty());
            assertEquals(1, active.get());
        }
        assertEquals(0, active.get());
        var replacement =
                registry.registerConsumers("probe", List.of(consumer("a")), () -> () -> {});
        registration.close();
        assertEquals(1, registry.size());
        replacement.close();
        assertTrue(registry.isEmpty());
    }

    @Test
    void drainingConsumerIsNotAdmitted() throws Exception {
        var registry = new PlatformEventConsumerRegistry(List.of());
        try (var registration =
                registry.registerConsumers("probe", List.of(consumer("a")), () -> null)) {
            assertTrue(registry.acquire("plugin", "a").isEmpty());
            assertEquals(1, registry.eventTypes().size());
        }
    }
}
