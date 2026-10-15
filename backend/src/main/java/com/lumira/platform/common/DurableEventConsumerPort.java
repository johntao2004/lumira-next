package com.lumira.platform.common;

import java.util.Map;
import java.util.UUID;

/**
 * Handles one event using database effects that participate in the worker's acknowledgement
 * transaction. Implementations must avoid network and other external effects; those require a
 * separate durable outbox and are not covered by this transaction.
 */
public interface DurableEventConsumerPort {
    record Event(
            UUID id,
            String sourceType,
            String eventType,
            String eventKey,
            String payload,
            int attempts) {}

    /**
     * Safe aggregate information about transactional inbox effects; never contains message text.
     */
    record ConsumptionResult(
            boolean known,
            int expectedCount,
            int inboxCreated,
            int inboxSkipped,
            Map<String, Integer> skippedByReason,
            String resultCode) {
        public ConsumptionResult {
            if (expectedCount < 0 || inboxCreated < 0 || inboxSkipped < 0)
                throw new IllegalArgumentException("Invalid consumer result counts");
            skippedByReason = skippedByReason == null ? Map.of() : Map.copyOf(skippedByReason);
            if (skippedByReason.values().stream().anyMatch(value -> value == null || value < 0))
                throw new IllegalArgumentException("Invalid consumer skip counts");
            if (known && inboxCreated + inboxSkipped != expectedCount)
                throw new IllegalArgumentException(
                        "Consumer result counts must match expected count");
            if (known
                    && skippedByReason.values().stream().mapToInt(Integer::intValue).sum()
                            != inboxSkipped)
                throw new IllegalArgumentException(
                        "Consumer skip reasons must match skipped count");
        }

        public static ConsumptionResult unknown() {
            return new ConsumptionResult(false, 0, 0, 0, Map.of(), null);
        }

        public static ConsumptionResult of(
                int expectedCount,
                int inboxCreated,
                Map<String, Integer> skippedByReason,
                String resultCode) {
            int skipped =
                    skippedByReason == null
                            ? 0
                            : skippedByReason.values().stream().mapToInt(Integer::intValue).sum();
            return new ConsumptionResult(
                    true, expectedCount, inboxCreated, skipped, skippedByReason, resultCode);
        }
    }

    String sourceType();

    String eventType();

    void consume(Event event) throws Exception;

    /** Existing consumers can keep implementing {@link #consume(Event)}. */
    default ConsumptionResult consumeWithResult(Event event) throws Exception {
        consume(event);
        return ConsumptionResult.unknown();
    }
}
