package com.lumira.platform.common;

import java.util.Map;
import java.util.UUID;

/** Writes a durable event in the caller's business transaction. */
public interface DurableEventPublisherPort {
    record Operation(
            UUID operationId,
            String operationType,
            String businessType,
            UUID businessId,
            Long businessVersion,
            UUID auditOperationId,
            int expectedEventCount,
            int expectedInboxCount,
            String outcomeCode) {}

    boolean publish(UUID id, String sourceType, String eventType, String eventKey, String payload);

    /** Optional correlation metadata. Older module adapters remain compatible with this default. */
    default void recordOperation(Operation operation) {}

    /** Optional outcome update for business operations that intentionally emit no event. */
    default void updateOperationOutcome(UUID operationId, String outcomeCode) {}

    /** Persist a completed business operation that intentionally produced no event. */
    default void completeOperationWithoutEvents(
            UUID operationId,
            int inboxCreated,
            int inboxSkipped,
            Map<String, Integer> skippedByReason,
            String outcomeCode) {}

    /** Publish an event associated with a previously recorded business operation. */
    default boolean publish(
            UUID id,
            String sourceType,
            String eventType,
            String eventKey,
            String payload,
            UUID operationId) {
        return publish(id, sourceType, eventType, eventKey, payload);
    }
}
