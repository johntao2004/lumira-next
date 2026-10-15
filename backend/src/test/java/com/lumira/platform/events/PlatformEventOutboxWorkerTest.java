package com.lumira.platform.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.DurableEventConsumerPort;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlatformEventOutboxWorkerTest {
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
