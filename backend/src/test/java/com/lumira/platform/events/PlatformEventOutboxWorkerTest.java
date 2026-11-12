package com.lumira.platform.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.DurableEventConsumerPort;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlatformEventOutboxWorkerTest {
    @Test
    void configuredConcurrencyBoundsSimultaneousConsumers() throws Exception {
        var store = mock(PlatformEventOutboxStore.class);
        var claims =
                new java.util.concurrent.ConcurrentLinkedQueue<PlatformEventOutboxStore.Claim>();
        for (int i = 0; i < 20; i++)
            claims.add(new PlatformEventOutboxStore.Claim(UUID.randomUUID(), UUID.randomUUID(), 1));
        var entered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var completed = new java.util.concurrent.CountDownLatch(20);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var maximum = new java.util.concurrent.atomic.AtomicInteger();
        var consumer =
                new DurableEventConsumerPort() {
                    public String sourceType() {
                        return "bounded";
                    }

                    public String eventType() {
                        return "created";
                    }

                    public void consume(Event event) throws Exception {
                        maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                        entered.countDown();
                        try {
                            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        } finally {
                            active.decrementAndGet();
                        }
                    }
                };
        when(store.claim()).thenAnswer(i -> Optional.ofNullable(claims.poll()));
        when(store.event(any()))
                .thenAnswer(
                        i ->
                                Optional.of(
                                        new PlatformEventOutboxStore.Event(
                                                ((PlatformEventOutboxStore.Claim) i.getArgument(0))
                                                        .id(),
                                                "bounded",
                                                "created",
                                                "key",
                                                "{}",
                                                1)));
        when(store.deliver(any(), any()))
                .thenAnswer(
                        i -> {
                            completed.countDown();
                            return true;
                        });
        try (var worker =
                new PlatformEventOutboxWorker(
                        store,
                        new PlatformEventConsumerRegistry(
                                java.util.List.of(consumer), java.util.List.of()),
                        (org.springframework.transaction.support.TransactionTemplate) null,
                        2)) {
            worker.poll();
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            worker.poll();
            assertEquals(18, claims.size());
            release.countDown();
            assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(2, maximum.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void onePollDrainsBacklogBeyondEightWithoutWaitingForAnotherTick() throws Exception {
        var store = mock(PlatformEventOutboxStore.class);
        var pending =
                new java.util.concurrent.ConcurrentLinkedQueue<PlatformEventOutboxStore.Claim>();
        for (int i = 0; i < 100; i++)
            pending.add(
                    new PlatformEventOutboxStore.Claim(UUID.randomUUID(), UUID.randomUUID(), 1));
        var completed = new java.util.concurrent.CountDownLatch(100);
        when(store.claim()).thenAnswer(invocation -> Optional.ofNullable(pending.poll()));
        when(store.event(any()))
                .thenAnswer(
                        invocation -> {
                            var claim = (PlatformEventOutboxStore.Claim) invocation.getArgument(0);
                            return Optional.of(
                                    new PlatformEventOutboxStore.Event(
                                            claim.id(), "unknown", "event", "key", "{}", 1));
                        });
        when(store.releaseWithoutConsumer(any()))
                .thenAnswer(
                        invocation -> {
                            completed.countDown();
                            return true;
                        });
        try (var worker =
                new PlatformEventOutboxWorker(
                        store, new PlatformEventConsumerRegistry(java.util.List.of()))) {
            worker.poll();
            assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            verify(store, times(100)).releaseWithoutConsumer(any());
        }
    }

    @Test
    void consumesThroughTheCommonAdapterAndAcknowledgesWithTheClaim() {
        var store = mock(PlatformEventOutboxStore.class);
        var claim = new PlatformEventOutboxStore.Claim(UUID.randomUUID(), UUID.randomUUID(), 1);
        var event =
                new PlatformEventOutboxStore.Event(
                        claim.id(), "source", "event", "key", "{\"ok\":true}", 1);
        var consumer =
                new PlatformEventConsumer() {
                    @Override
                    public String sourceType() {
                        return "source";
                    }

                    @Override
                    public String eventType() {
                        return "event";
                    }

                    @Override
                    public void consume(PlatformEventOutboxStore.Event actual) {
                        assertEquals(event, actual);
                    }
                };
        when(store.event(claim)).thenReturn(Optional.of(event));
        when(store.deliver(eq(claim), any(DurableEventConsumerPort.ConsumptionResult.class)))
                .thenReturn(true);
        var worker =
                new PlatformEventOutboxWorker(
                        store, new PlatformEventConsumerRegistry(java.util.List.of(consumer)));
        try {
            worker.process(claim);
            verify(store).deliver(claim, DurableEventConsumerPort.ConsumptionResult.unknown());
            verify(store, never()).fail(any(), anyString());
        } finally {
            worker.close();
        }
    }

    @Test
    void failedOrUnregisteredHandlersAreRetryableAndNeverAcknowledged() {
        var store = mock(PlatformEventOutboxStore.class);
        var claim = new PlatformEventOutboxStore.Claim(UUID.randomUUID(), UUID.randomUUID(), 1);
        var event =
                new PlatformEventOutboxStore.Event(
                        claim.id(), "source", "event", "key", "{\"ok\":true}", 1);
        when(store.event(claim)).thenReturn(Optional.of(event));
        var failing =
                new PlatformEventConsumer() {
                    @Override
                    public String sourceType() {
                        return "source";
                    }

                    @Override
                    public String eventType() {
                        return "event";
                    }

                    @Override
                    public void consume(PlatformEventOutboxStore.Event ignored) throws Exception {
                        throw new Exception("transport");
                    }
                };
        var worker =
                new PlatformEventOutboxWorker(
                        store, new PlatformEventConsumerRegistry(java.util.List.of(failing)));
        try {
            worker.process(claim);
            verify(store).fail(claim, "CONSUMER_FAILED");
            verify(store, never()).deliver(any(), any());
        } finally {
            worker.close();
        }

        var noHandler =
                new PlatformEventOutboxWorker(
                        store, new PlatformEventConsumerRegistry(java.util.List.of()));
        try {
            noHandler.process(claim);
            verify(store).releaseWithoutConsumer(claim);
        } finally {
            noHandler.close();
        }
    }
}
