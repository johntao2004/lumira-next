package com.lumira.platform.events;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.PlatformPageId;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Read-only, metadata-only event and operation trace API. */
@Service
public class PlatformEventTraceQuery {
    public record Result(
            boolean known,
            Integer expectedInboxCount,
            Integer inboxCreated,
            Integer inboxSkipped,
            Map<String, Integer> skippedByReason,
            String resultCode,
            OffsetDateTime completedAt) {
        public Result {
            skippedByReason = skippedByReason == null ? Map.of() : Map.copyOf(skippedByReason);
        }

        public static Result unknown(String resultCode) {
            return new Result(false, null, null, null, Map.of(), resultCode, null);
        }
    }

    public record EventMetadata(
            UUID id,
            UUID operationId,
            String sourceType,
            String eventType,
            String status,
            int attempts,
            int generation,
            String lastErrorCode,
            OffsetDateTime availableAt,
            OffsetDateTime deliveredAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Result result) {}

    public record HistoryEntry(
            long sequence,
            OffsetDateTime occurredAt,
            int generation,
            int attempt,
            String phase,
            String status,
            OffsetDateTime nextRetryAt,
            String errorCode,
            UUID auditOperationId,
            String actor,
            String reason,
            Result result) {}

    public record EventTrace(
            EventMetadata event,
            OperationMetadata operation,
            String historyStatus,
            PageResult<HistoryEntry> history) {}

    public record OperationResult(
            boolean known,
            int expectedInboxCount,
            int inboxCreated,
            int inboxSkipped,
            Map<String, Integer> skippedByReason,
            String resultCode,
            OffsetDateTime completedAt) {
        public OperationResult {
            skippedByReason = skippedByReason == null ? Map.of() : Map.copyOf(skippedByReason);
        }
    }

    public record OperationMetadata(
            UUID operationId,
            String operationType,
            String businessType,
            UUID businessId,
            Long businessVersion,
            UUID auditOperationId,
            String outcomeCode,
            int expectedEventCount,
            int completedEventCount,
            String historyStatus,
            OperationResult result) {}

    public record OperationEvent(EventMetadata event) {}

    public record OperationTrace(
            OperationMetadata operation,
            List<OperationEvent> items,
            long total,
            int page,
            int pageSize,
            String historyStatus) {
        public OperationTrace {
            items = List.copyOf(items);
        }
    }

    private record RawEvent(
            UUID id,
            UUID operationId,
            String sourceType,
            String eventType,
            String status,
            int attempts,
            int generation,
            String lastErrorCode,
            OffsetDateTime availableAt,
            OffsetDateTime deliveredAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            boolean resultKnown,
            Integer expectedCount,
            Integer inboxCreated,
            Integer inboxSkipped,
            Map<String, Integer> skippedByReason,
            String resultCode,
            OffsetDateTime resultCompletedAt) {}

    private record RawHistory(
            long sequence,
            OffsetDateTime occurredAt,
            int generation,
            int attempt,
            String phase,
            String status,
            OffsetDateTime nextRetryAt,
            String errorCode,
            UUID auditOperationId,
            String actor,
            String reason,
            Integer expectedCount,
            Integer inboxCreated,
            Integer inboxSkipped,
            Map<String, Integer> skippedByReason,
            String resultCode) {}

    private record RawOperation(
            UUID operationId,
            String operationType,
            String businessType,
            UUID businessId,
            Long businessVersion,
            UUID auditOperationId,
            String outcomeCode,
            int expectedEventCount,
            int completedEventCount,
            int expectedInboxCount,
            int inboxCreated,
            int inboxSkipped,
            Map<String, Integer> skippedByReason,
            boolean resultKnown,
            int expiredEventCount,
            String historyStatus,
            OffsetDateTime completedAt) {}

    private record AuditReference(UUID targetId) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AdministrationPort guard;

    public PlatformEventTraceQuery(JdbcClient jdbc, ObjectMapper mapper, AdministrationPort guard) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.guard = guard;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EventTrace event(String actor, UUID eventId, int page, int pageSize) {
        requirePage(page, pageSize);
        guard.requirePageGlobal(actor, PlatformPageId.EVENT_OUTBOX);
        RawEvent raw = eventRow(eventId).orElseThrow(() -> notFound("eventNotFound"));
        Optional<RawOperation> rawOperation = operationRowById(raw.operationId());
        OperationMetadata operation =
                rawOperation
                        .map(value -> operationMetadata(value, effectiveHistoryStatus(value)))
                        .orElse(null);
        long total =
                jdbc.sql("SELECT count(*) FROM platform_event_trace WHERE event_id=:id")
                        .param("id", eventId)
                        .query(Long.class)
                        .single();
        String historyStatus = eventHistoryStatus(eventId, total, raw.attempts(), raw.generation());
        var history =
                jdbc
                        .sql(
                                """
                                SELECT trace_sequence,occurred_at,generation,attempt,phase,status,
                                  next_retry_at,error_code,audit_operation_id,actor,reason,
                                  expected_count,inbox_created,inbox_skipped,skipped_by_reason::text,result_code
                                FROM platform_event_trace WHERE event_id=:id
                                ORDER BY trace_sequence LIMIT :limit OFFSET :offset
                                """)
                        .param("id", eventId)
                        .param("limit", pageSize)
                        .param("offset", (long) (page - 1) * pageSize)
                        .query((rs, n) -> historyRow(rs, n))
                        .list()
                        .stream()
                        .map(this::historyEntry)
                        .toList();
        return new EventTrace(
                eventMetadata(raw),
                operation,
                historyStatus,
                new PageResult<>(history, total, page, pageSize));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OperationTrace operation(String actor, UUID auditOperationId, int page, int pageSize) {
        requirePage(page, pageSize);
        guard.requirePageGlobal(actor, PlatformPageId.EVENT_OUTBOX);
        guard.requirePageGlobal(actor, PlatformPageId.AUDIT);
        AuditReference auditReference =
                auditOperationId == null
                        ? null
                        : jdbc.sql("SELECT target_id FROM platform_audit WHERE id=:id")
                                .param("id", auditOperationId)
                                .query(
                                        (rs, n) ->
                                                new AuditReference(
                                                        rs.getObject("target_id", UUID.class)))
                                .optional()
                                .orElse(null);
        if (auditReference == null) throw notFound("auditOperationNotFound");

        Optional<RawOperation> rawOperation = operationRow(auditOperationId);
        if (rawOperation.isPresent()) {
            RawOperation operation = rawOperation.get();
            String historyStatus = effectiveHistoryStatus(operation);
            OperationMetadata metadata = operationMetadata(operation, historyStatus);
            long total =
                    jdbc.sql("SELECT count(*) FROM platform_event_outbox WHERE operation_id=:id")
                            .param("id", operation.operationId())
                            .query(Long.class)
                            .single();
            var rows =
                    jdbc.sql(
                                    eventSelect()
                                            + " WHERE e.operation_id=:id ORDER BY e.created_at,e.id"
                                            + " LIMIT :limit OFFSET :offset")
                            .param("id", operation.operationId())
                            .param("limit", pageSize)
                            .param("offset", (long) (page - 1) * pageSize)
                            .query((rs, n) -> eventRow(rs, n))
                            .list();
            return new OperationTrace(
                    metadata,
                    rows.stream().map(row -> new OperationEvent(eventMetadata(row))).toList(),
                    total,
                    page,
                    pageSize,
                    historyStatus);
        }

        // Replay approvals are their own exact audit operation but intentionally do not create a
        // second business operation. Expose only events whose trace rows reference this audit ID.
        long replayTotal =
                jdbc.sql(
                                "SELECT count(DISTINCT event_id) FROM platform_event_trace WHERE"
                                        + " audit_operation_id=:auditId")
                        .param("auditId", auditOperationId)
                        .query(Long.class)
                        .single();
        if (replayTotal == 0)
            return new OperationTrace(null, List.of(), 0, page, pageSize, "NOT_RECORDED");
        var replayEvents =
                jdbc.sql(
                                eventSelect()
                                        + " JOIN (SELECT DISTINCT event_id FROM"
                                        + " platform_event_trace WHERE audit_operation_id=:auditId)"
                                        + " correlated ON correlated.event_id=e.id ORDER BY"
                                        + " e.created_at,e.id LIMIT :limit OFFSET :offset")
                        .param("auditId", auditOperationId)
                        .param("limit", pageSize)
                        .param("offset", (long) (page - 1) * pageSize)
                        .query((rs, n) -> eventRow(rs, n))
                        .list();
        UUID replayEventId =
                jdbc.sql(
                                "SELECT event_id FROM platform_event_trace WHERE"
                                        + " audit_operation_id=:auditId ORDER BY trace_sequence"
                                        + " LIMIT 1")
                        .param("auditId", auditOperationId)
                        .query(UUID.class)
                        .optional()
                        .orElse(auditReference.targetId());
        RawEvent replayEvent = eventRow(replayEventId).orElse(null);
        long completedEventCount =
                jdbc.sql(
                                "SELECT count(DISTINCT e.id) FROM platform_event_outbox e JOIN"
                                        + " (SELECT DISTINCT event_id FROM platform_event_trace"
                                        + " WHERE audit_operation_id=:auditId) correlated"
                                        + " ON correlated.event_id=e.id WHERE e.status='DELIVERED'")
                        .param("auditId", auditOperationId)
                        .query(Long.class)
                        .single();
        String replayOutcomeCode =
                completedEventCount == replayTotal
                        ? replayEvent != null && replayEvent.resultKnown()
                                ? safeCode(replayEvent.resultCode())
                                : "DELIVERED"
                        : jdbc.sql(
                                        "WITH correlated AS (SELECT DISTINCT event_id FROM"
                                                + " platform_event_trace WHERE"
                                                + " audit_operation_id=:auditId) SELECT CASE WHEN"
                                                + " EXISTS (SELECT 1 FROM platform_event_outbox e JOIN"
                                                + " correlated c ON c.event_id=e.id WHERE"
                                                + " e.status='DEAD_LETTER') THEN 'DEAD_LETTER' WHEN"
                                                + " EXISTS (SELECT 1 FROM platform_event_outbox e JOIN"
                                                + " correlated c ON c.event_id=e.id WHERE"
                                                + " e.status='FAILED') THEN 'FAILED' WHEN EXISTS"
                                                + " (SELECT 1 FROM platform_event_outbox e JOIN"
                                                + " correlated c ON c.event_id=e.id WHERE"
                                                + " e.status='PENDING' AND e.last_error='NO_CONSUMER')"
                                                + " THEN 'NO_CONSUMER' WHEN EXISTS (SELECT 1 FROM"
                                                + " platform_event_outbox e JOIN correlated c ON"
                                                + " c.event_id=e.id WHERE e.status='PENDING' AND"
                                                + " e.last_error IN"
                                                + " ('CONSUMER_FAILED','LEASE_EXPIRED')) THEN"
                                                + " 'RETRY_PENDING' ELSE 'EVENTS_PENDING' END")
                                .param("auditId", auditOperationId)
                                .query(String.class)
                                .single();
        boolean resultKnown =
                replayTotal == 1
                        && completedEventCount == 1
                        && replayEvent != null
                        && replayEvent.resultKnown();
        OperationResult replayResult =
                resultKnown
                        ? new OperationResult(
                                true,
                                replayEvent.expectedCount(),
                                replayEvent.inboxCreated(),
                                replayEvent.inboxSkipped(),
                                replayEvent.skippedByReason(),
                                safeCode(replayEvent.resultCode()),
                                replayEvent.resultCompletedAt())
                        : new OperationResult(
                                false,
                                0,
                                0,
                                0,
                                Map.of(),
                                replayOutcomeCode,
                                completedEventCount == replayTotal && replayEvent != null
                                        ? replayEvent.deliveredAt()
                                        : null);
        OperationMetadata replay =
                new OperationMetadata(
                        null,
                        "event.replay",
                        "EVENT",
                        replayEventId,
                        null,
                        auditOperationId,
                        replayOutcomeCode,
                        Math.toIntExact(replayTotal),
                        Math.toIntExact(completedEventCount),
                        "AVAILABLE",
                        replayResult);
        return new OperationTrace(
                replay,
                replayEvents.stream().map(row -> new OperationEvent(eventMetadata(row))).toList(),
                replayTotal,
                page,
                pageSize,
                "AVAILABLE");
    }

    private Optional<RawOperation> operationRow(UUID auditOperationId) {
        return readOperation(
                "FROM platform_event_operation WHERE audit_operation_id=:id", auditOperationId);
    }

    private Optional<RawOperation> operationRowById(UUID operationId) {
        if (operationId == null) return Optional.empty();
        return readOperation("FROM platform_event_operation WHERE operation_id=:id", operationId);
    }

    private Optional<RawOperation> readOperation(String predicate, UUID id) {
        return jdbc.sql(
                        """
                        SELECT operation_id,operation_type,business_type,business_id,business_version,
                          audit_operation_id,outcome_code,expected_event_count,completed_event_count,
                          expected_inbox_count,inbox_created,inbox_skipped,skipped_by_reason::text,
                          result_known,expired_event_count,history_status,completed_at
                        """
                                + predicate)
                .param("id", id)
                .query(
                        (rs, n) ->
                                new RawOperation(
                                        rs.getObject("operation_id", UUID.class),
                                        rs.getString("operation_type"),
                                        rs.getString("business_type"),
                                        rs.getObject("business_id", UUID.class),
                                        rs.getObject("business_version", Long.class),
                                        rs.getObject("audit_operation_id", UUID.class),
                                        rs.getString("outcome_code"),
                                        rs.getInt("expected_event_count"),
                                        rs.getInt("completed_event_count"),
                                        rs.getInt("expected_inbox_count"),
                                        rs.getInt("inbox_created"),
                                        rs.getInt("inbox_skipped"),
                                        readCounts(rs.getString("skipped_by_reason")),
                                        rs.getBoolean("result_known"),
                                        rs.getInt("expired_event_count"),
                                        rs.getString("history_status"),
                                        rs.getObject("completed_at", OffsetDateTime.class)))
                .optional();
    }

    private Optional<RawEvent> eventRow(UUID eventId) {
        return jdbc.sql(eventSelect() + " WHERE e.id=:id")
                .param("id", eventId)
                .query((rs, n) -> eventRow(rs, n))
                .optional();
    }

    private String eventSelect() {
        return """
        SELECT e.id,e.operation_id,e.source_type,e.event_type,e.status,e.attempts,e.generation,
          e.last_error,e.available_at,e.delivered_at,e.created_at,e.updated_at,
          (latest.phase='SUCCEEDED' AND latest.expected_count IS NOT NULL) AS result_known,
          latest.expected_count,latest.inbox_created,latest.inbox_skipped,
          COALESCE(latest.skipped_by_reason::text,'{}') AS skipped_by_reason,
          latest.result_code,latest.occurred_at AS result_completed_at
        FROM platform_event_outbox e LEFT JOIN LATERAL (
          SELECT * FROM platform_event_trace t WHERE t.event_id=e.id AND t.phase='SUCCEEDED'
          ORDER BY t.trace_sequence DESC LIMIT 1
        ) latest ON true
        """;
    }

    private RawEvent eventRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RawEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("operation_id", UUID.class),
                rs.getString("source_type"),
                rs.getString("event_type"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getInt("generation"),
                safeErrorCode(rs.getString("last_error")),
                rs.getObject("available_at", OffsetDateTime.class),
                rs.getObject("delivered_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getBoolean("result_known"),
                (Integer) rs.getObject("expected_count"),
                (Integer) rs.getObject("inbox_created"),
                (Integer) rs.getObject("inbox_skipped"),
                readCounts(rs.getString("skipped_by_reason")),
                rs.getString("result_code"),
                rs.getObject("result_completed_at", OffsetDateTime.class));
    }

    private HistoryEntry historyEntry(RawHistory row) {
        boolean known = row.expectedCount() != null;
        Result result =
                new Result(
                        known,
                        row.expectedCount(),
                        row.inboxCreated(),
                        row.inboxSkipped(),
                        row.skippedByReason(),
                        row.resultCode(),
                        known ? row.occurredAt() : null);
        return new HistoryEntry(
                row.sequence(),
                row.occurredAt(),
                row.generation(),
                row.attempt(),
                row.phase(),
                row.status(),
                row.nextRetryAt(),
                row.errorCode(),
                row.auditOperationId(),
                row.actor(),
                row.reason(),
                result);
    }

    private RawHistory historyRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RawHistory(
                rs.getLong("trace_sequence"),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getInt("generation"),
                rs.getInt("attempt"),
                rs.getString("phase"),
                rs.getString("status"),
                rs.getObject("next_retry_at", OffsetDateTime.class),
                safeErrorCode(rs.getString("error_code")),
                rs.getObject("audit_operation_id", UUID.class),
                rs.getString("actor"),
                safeReason(rs.getString("reason")),
                (Integer) rs.getObject("expected_count"),
                (Integer) rs.getObject("inbox_created"),
                (Integer) rs.getObject("inbox_skipped"),
                readCounts(rs.getString("skipped_by_reason")),
                safeCode(rs.getString("result_code")));
    }

    private EventMetadata eventMetadata(RawEvent raw) {
        Result result =
                raw.resultKnown()
                        ? new Result(
                                true,
                                raw.expectedCount(),
                                raw.inboxCreated(),
                                raw.inboxSkipped(),
                                raw.skippedByReason(),
                                safeCode(raw.resultCode()),
                                raw.resultCompletedAt())
                        : Result.unknown(null);
        return new EventMetadata(
                raw.id(),
                raw.operationId(),
                raw.sourceType(),
                raw.eventType(),
                raw.status(),
                raw.attempts(),
                raw.generation(),
                raw.lastErrorCode(),
                raw.availableAt(),
                raw.deliveredAt(),
                raw.createdAt(),
                raw.updatedAt(),
                result);
    }

    private OperationMetadata operationMetadata(RawOperation row, String historyStatus) {
        boolean known = row.resultKnown();
        OperationResult result =
                new OperationResult(
                        known,
                        row.expectedInboxCount(),
                        row.inboxCreated(),
                        row.inboxSkipped(),
                        row.skippedByReason(),
                        row.outcomeCode(),
                        row.completedAt());
        return new OperationMetadata(
                row.operationId(),
                row.operationType(),
                row.businessType(),
                row.businessId(),
                row.businessVersion(),
                row.auditOperationId(),
                row.outcomeCode(),
                row.expectedEventCount(),
                row.completedEventCount(),
                historyStatus,
                result);
    }

    private String effectiveHistoryStatus(RawOperation operation) {
        if (operation.expectedEventCount() == 0) return "NO_EVENTS";
        if (operation.expiredEventCount() >= operation.expectedEventCount()) return "EXPIRED";
        if (operation.expiredEventCount() > 0) return "PARTIAL";
        long retained =
                jdbc.sql("SELECT count(*) FROM platform_event_outbox WHERE operation_id=:id")
                        .param("id", operation.operationId())
                        .query(Long.class)
                        .single();
        return retained == 0 ? "EXPIRED" : operation.historyStatus();
    }

    private String eventHistoryStatus(UUID eventId, long total, int attempts, int generation) {
        if (total == 0) return attempts > 0 || generation > 0 ? "PARTIAL" : "NOT_RECORDED";
        var first =
                jdbc.sql(
                                "SELECT phase,generation,attempt FROM platform_event_trace WHERE"
                                        + " event_id=:id ORDER BY trace_sequence LIMIT 1")
                        .param("id", eventId)
                        .query(
                                (rs, n) ->
                                        new Object[] {
                                            rs.getString("phase"),
                                            rs.getInt("generation"),
                                            rs.getInt("attempt")
                                        })
                        .single();
        return "CLAIMED".equals(first[0]) && ((Integer) first[1]) == 0 && ((Integer) first[2]) == 1
                ? "AVAILABLE"
                : "PARTIAL";
    }

    private Map<String, Integer> readCounts(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            var node = mapper.readTree(json);
            var counts = new LinkedHashMap<String, Integer>();
            node.properties()
                    .forEach(
                            entry -> {
                                if (entry.getValue().canConvertToInt())
                                    counts.put(entry.getKey(), entry.getValue().intValue());
                            });
            return Map.copyOf(counts);
        } catch (RuntimeException malformed) {
            return Map.of();
        }
    }

    private static String safeCode(String value) {
        if (value == null || value.isBlank()) return null;
        String code = value.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_:-]", "_");
        return code.substring(0, Math.min(64, code.length()));
    }

    private static String safeErrorCode(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "CONSUMER_FAILED", "NO_CONSUMER", "LEASE_EXPIRED", "UNSPECIFIED" ->
                    value.strip().toUpperCase(Locale.ROOT);
            default -> "OTHER_ERROR";
        };
    }

    private static String safeReason(String value) {
        if (value == null || value.isBlank()) return null;
        String reason = value.strip().replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "_");
        return reason.substring(0, Math.min(1000, reason.length()));
    }

    private static void requirePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidPagination");
    }

    private static ResponseStatusException notFound(String code) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, code);
    }
}
