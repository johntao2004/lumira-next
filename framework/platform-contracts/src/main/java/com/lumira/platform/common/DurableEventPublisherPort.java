package com.lumira.platform.common;

import java.util.Map;
import java.util.UUID;

/** 在调用方业务事务内写入持久事件。 Writes a durable event in the caller's business transaction. */
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

    /**
     * 在业务事务中写入事件；返回是否新增，去重不新增。 Writes within the business transaction; returns whether a new event
     * was inserted rather than deduplicated.
     */
    boolean publish(UUID id, String sourceType, String eventType, String eventKey, String payload);

    /**
     * 可选关联元数据；默认方法兼容旧模块适配器。 Optional correlation metadata. Older module adapters remain compatible
     * with this default.
     */
    default void recordOperation(Operation operation) {}

    /**
     * 为有意不发事件的业务操作更新可选结果。 Optional outcome update for business operations that intentionally emit
     * no event.
     */
    default void updateOperationOutcome(UUID operationId, String outcomeCode) {}

    /**
     * 持久化已完成且有意不发事件的业务操作。 Persist a completed business operation that intentionally produced no
     * event.
     */
    default void completeOperationWithoutEvents(
            UUID operationId,
            int inboxCreated,
            int inboxSkipped,
            Map<String, Integer> skippedByReason,
            String outcomeCode) {}

    /**
     * 仅完成发布去重且尚未关联事件的单事件操作。 Complete only a single-event operation whose publication was
     * deduplicated before linking.
     */
    default void completeDuplicateSingleEventOperation(UUID operationId) {}

    /**
     * 发布关联已登记业务操作的事件。 Publish an event associated with a previously recorded business operation.
     */
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
