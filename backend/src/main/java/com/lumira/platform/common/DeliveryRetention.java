package com.lumira.platform.common;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded cleanup for encrypted terminal delivery rows; audit history is retained separately. */
@Component
public final class DeliveryRetention {
    private final List<TerminalDeliveryQueue> queues;
    private final Duration retention;
    private final int batchSize;

    public DeliveryRetention(
            List<TerminalDeliveryQueue> queues,
            @Value("${lumira.delivery.terminal-retention:30d}") Duration retention,
            @Value("${lumira.delivery.retention-batch:1000}") int batchSize) {
        if (queues == null || queues.isEmpty()) {
            throw new IllegalArgumentException("At least one delivery queue is required");
        }
        this.queues = List.copyOf(queues);
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("Delivery retention must be positive");
        }
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("Delivery retention batch must be between 1 and 10000");
        }
        this.retention = retention;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${lumira.delivery.retention-interval-ms:86400000}",
            initialDelayString = "${lumira.delivery.retention-initial-delay-ms:86400000}")
    public void purge() {
        queues.forEach(queue -> queue.purgeTerminal(retention, batchSize));
    }
}
