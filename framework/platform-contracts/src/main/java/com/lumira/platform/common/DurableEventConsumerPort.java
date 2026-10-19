package com.lumira.platform.common;

import java.util.Map;
import java.util.UUID;

/**
 * 消费者数据库写入与 worker 确认共用事务；网络等外部副作用须另用持久投递，不在此原子保证内。 Handles one event using database effects that
 * participate in the worker's acknowledgement transaction. Implementations must avoid network and
 * other external effects; those require a separate durable outbox and are not covered by this
 * transaction.
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
     * 事务内收件箱效果的安全汇总，不包含消息正文。 Safe aggregate information about transactional inbox effects; never
     * contains message text.
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

    /**
     * 返回消费契约的来源标识，须与生产端一致。 Returns the consumer source identifier matching the producer contract.
     */
    String sourceType();

    /** 返回消费事件类型，须与生产端一致。 Returns the event type matching the producer contract. */
    String eventType();

    /**
     * 在确认事务内处理事件的数据库效果；异常必须传播以回滚，不在此执行外部副作用。 Applies database effects within the acknowledgement
     * transaction; propagate failures for rollback and avoid external side effects.
     */
    void consume(Event event) throws Exception;

    /**
     * 已有消费者可继续实现 consume(Event)。 Existing consumers can keep implementing {@link #consume(Event)}.
     */
    default ConsumptionResult consumeWithResult(Event event) throws Exception {
        consume(event);
        return ConsumptionResult.unknown();
    }
}
