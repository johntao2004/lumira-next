package com.lumira.platform.events;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded, horizontally-safe Outbox delivery. PostgreSQL SKIP LOCKED owns the claim; this worker
 * only performs handler IO and fences the final ack with the claim token. Multiple application
 * instances can therefore poll concurrently.
 */
@Component
public final class PlatformEventOutboxWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PlatformEventOutboxWorker.class);
    private static final int MAX_IN_FLIGHT = 8;

    private final PlatformEventOutboxStore store;
    private final PlatformEventConsumerRegistry registry;
    private final ExecutorService workers =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("platform-event-", 0).factory());
    private final Semaphore slots = new Semaphore(MAX_IN_FLIGHT);
    private final AtomicBoolean stopped = new AtomicBoolean();

    public PlatformEventOutboxWorker(
            PlatformEventOutboxStore store, PlatformEventConsumerRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    @Scheduled(
            fixedDelayString = "${lumira.events.poll-interval-ms:2000}",
            initialDelayString = "${lumira.events.poll-initial-delay-ms:15000}")
    public void poll() {
        if (stopped.get() || registry.isEmpty()) return;
        for (int i = 0; i < MAX_IN_FLIGHT && !stopped.get() && slots.tryAcquire(); i++) {
            boolean submitted = false;
            try {
                var claim = store.claim();
                if (claim.isEmpty()) return;
                workers.execute(
                        () -> {
                            try {
                                process(claim.get());
                            } finally {
                                slots.release();
                            }
                        });
                submitted = true;
            } catch (RuntimeException failure) {
                log.warn(
                        "Platform event polling deferred ({})", failure.getClass().getSimpleName());
            } finally {
                if (!submitted) slots.release();
            }
        }
    }

    void process(PlatformEventOutboxStore.Claim claim) {
        var event = store.event(claim).orElse(null);
        if (event == null) return;
        var consumer = registry.resolve(event.sourceType(), event.eventType());
        if (consumer.isEmpty()) {
            store.fail(claim, "NO_CONSUMER");
            return;
        }
        try {
            // Never hold a JDBC transaction while a module performs network or
            // other potentially slow work. The claim token fences the ack.
            consumer.get().consume(event);
            if (!store.deliver(claim)) {
                log.debug("Platform event ack fenced for {}", claim.id());
            }
        } catch (Exception failure) {
            // Keep exception details out of the metadata API; operators can
            // correlate the claim id with redacted server logs instead.
            store.fail(claim, "CONSUMER_FAILED");
            log.warn(
                    "Platform event delivery deferred for {} ({})",
                    claim.id(),
                    failure.getClass().getSimpleName());
        }
    }

    @Override
    @PreDestroy
    public void close() {
        if (stopped.compareAndSet(false, true)) workers.shutdownNow();
    }
}
