package com.lumira.platform.events;

import com.lumira.platform.common.DurableEventConsumerPort;
import com.lumira.platform.common.DurableEventPublisherPort;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/** Durable, idempotent event boundary shared by platform modules and future business modules. */
@Repository
public class PlatformEventOutboxStore implements DurableEventPublisherPort {
    public static final int MAX_ATTEMPTS = 8;

    public record Claim(UUID id, UUID token, int attempts, int generation, UUID operationId) {
        public Claim(UUID id, UUID token, int attempts) {
            this(id, token, attempts, 0, null);
        }
    }

    public record Event(
            UUID id,
            UUID operationId,
            String sourceType,
            String eventType,
            String eventKey,
            String payload,
            int attempts,
            int generation) {
        public Event(
                UUID id,
                String sourceType,
                String eventType,
                String eventKey,
                String payload,
                int attempts) {
            this(id, null, sourceType, eventType, eventKey, payload, attempts, 0);
        }
    }

    public record Recovery(
            UUID id,
            String status,
            int attempts,
            int generation,
            UUID operationId,
            OffsetDateTime availableAt) {
        public Recovery(UUID id, String status) {
            this(id, status, 0, 0, null, null);
        }
    }

    public record Replay(UUID id, String status, int attempts, int generation) {
        public Replay(UUID id, String status, int attempts) {
            this(id, status, attempts, 0);
        }
    }

    private record UpdatedState(
            UUID eventId,
            UUID operationId,
            int attempts,
            int generation,
            String status,
            OffsetDateTime availableAt) {}

    private record ReplayState(UUID eventId, UUID operationId, int attempts, int generation) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PlatformEventOutboxStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** First write wins for the same source/event/key. Call from the producer transaction. */
    @Override
    @Transactional
    public boolean publish(
            UUID id, String sourceType, String eventType, String eventKey, String payload) {
        return append(id, sourceType, eventType, eventKey, payload);
    }

    @Override
    @Transactional
    public boolean publish(
            UUID id,
            String sourceType,
            String eventType,
            String eventKey,
            String payload,
            UUID operationId) {
        return append(id, sourceType, eventType, eventKey, payload, operationId);
    }

    @Override
    @Transactional
    public void recordOperation(DurableEventPublisherPort.Operation operation) {
        Objects.requireNonNull(operation);
        if (operation.operationId() == null
                || operation.businessId() == null
                || operation.operationType() == null
                || !operation.operationType().matches("[a-z][a-z0-9._:-]{0,63}")
                || operation.businessType() == null
                || !operation.businessType().matches("[A-Z][A-Z0-9_]{0,63}")
                || operation.expectedEventCount() < 0
                || operation.expectedInboxCount() < 0
                || operation.outcomeCode() == null
                || !operation.outcomeCode().matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("Invalid event operation");
        jdbc.sql(
                        """
                        INSERT INTO platform_event_operation(
                          operation_id,operation_type,business_type,business_id,business_version,
                          audit_operation_id,outcome_code,expected_event_count,expected_inbox_count,
                          history_status,completed_at)
                        VALUES(:id,:type,:businessType,:businessId,:businessVersion,:auditId,:outcome,
                          :eventCount,:inboxCount,CASE WHEN :eventCount=0 THEN 'NO_EVENTS' ELSE 'AVAILABLE' END,
                          CASE WHEN :eventCount=0 THEN now() ELSE NULL END)
                        """)
                .param("id", operation.operationId())
                .param("type", operation.operationType())
                .param("businessType", operation.businessType())
                .param("businessId", operation.businessId())
                .param("businessVersion", operation.businessVersion())
                .param("auditId", operation.auditOperationId())
                .param("outcome", operation.outcomeCode())
                .param("eventCount", operation.expectedEventCount())
                .param("inboxCount", operation.expectedInboxCount())
                .update();
    }

    @Override
    @Transactional
    public void updateOperationOutcome(UUID operationId, String outcomeCode) {
        if (operationId == null
                || outcomeCode == null
                || !outcomeCode.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("Invalid event operation outcome");
        jdbc.sql(
                        """
                        UPDATE platform_event_operation o SET outcome_code=CASE
                          WHEN EXISTS (SELECT 1 FROM platform_event_outbox e
                            WHERE e.operation_id=o.operation_id AND e.status='DEAD_LETTER') THEN 'DEAD_LETTER'
                          WHEN EXISTS (SELECT 1 FROM platform_event_outbox e
                            WHERE e.operation_id=o.operation_id AND e.status='FAILED') THEN 'FAILED'
                          WHEN EXISTS (SELECT 1 FROM platform_event_outbox e
                            WHERE e.operation_id=o.operation_id AND e.status='PENDING' AND e.last_error='NO_CONSUMER') THEN 'NO_CONSUMER'
                          WHEN EXISTS (SELECT 1 FROM platform_event_outbox e
                            WHERE e.operation_id=o.operation_id AND e.status='PENDING'
                              AND e.last_error IN ('CONSUMER_FAILED','LEASE_EXPIRED')) THEN 'RETRY_PENDING'
                          WHEN EXISTS (SELECT 1 FROM platform_event_outbox e
                            WHERE e.operation_id=o.operation_id AND e.status IN ('PENDING','PROCESSING')) THEN 'EVENTS_PENDING'
                          ELSE :outcome END,updated_at=now()
                        WHERE o.operation_id=:id
                        """)
                .param("outcome", outcomeCode)
                .param("id", operationId)
                .update();
    }

    @Override
    @Transactional
    public void completeOperationWithoutEvents(
            UUID operationId,
            int inboxCreated,
            int inboxSkipped,
            Map<String, Integer> skippedByReason,
            String outcomeCode) {
        Objects.requireNonNull(operationId);
        if (inboxCreated < 0
                || inboxSkipped < 0
                || outcomeCode == null
                || !outcomeCode.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("Invalid operation result");
        Map<String, Integer> reasons =
                skippedByReason == null ? Map.of() : Map.copyOf(skippedByReason);
        if (reasons.values().stream().anyMatch(value -> value == null || value < 0)
                || reasons.values().stream().mapToInt(Integer::intValue).sum() != inboxSkipped)
            throw new IllegalArgumentException("Invalid operation skip counts");
        jdbc.sql(
                        """
                        UPDATE platform_event_operation SET outcome_code=:outcome,
                          inbox_created=:created,inbox_skipped=:skipped,
                          skipped_by_reason=CAST(:reasons AS jsonb),result_known=true,
                          completed_at=now(),history_status='NO_EVENTS',updated_at=now()
                        WHERE operation_id=:id AND expected_event_count=0
                        """)
                .param("outcome", outcomeCode)
                .param("created", inboxCreated)
                .param("skipped", inboxSkipped)
                .param("reasons", mapper.writeValueAsString(reasons))
                .param("id", operationId)
                .update();
    }

    /** Legacy method retained for existing platform callers and tests. */
    @Transactional
    public boolean append(
            UUID id, String sourceType, String eventType, String eventKey, String payload) {
        return append(id, sourceType, eventType, eventKey, payload, null);
    }

    @Transactional
    public boolean append(
            UUID id,
            String sourceType,
            String eventType,
            String eventKey,
            String payload,
            UUID operationId) {
        Objects.requireNonNull(id);
        String normalized = normalize(sourceType, eventType, eventKey, payload);
        return jdbc.sql(
                                """
                                INSERT INTO platform_event_outbox(id,operation_id,source_type,event_type,event_key,payload)
                                VALUES(:id,:operation,:source,:type,:key,CAST(:payload AS jsonb))
                                ON CONFLICT DO NOTHING
                                """)
                        .param("id", id)
                        .param("operation", operationId)
                        .param("source", sourceType)
                        .param("type", eventType)
                        .param("key", eventKey)
                        .param("payload", normalized)
                        .update()
                == 1;
    }

    public Optional<Event> event(Claim claim) {
        return jdbc.sql(
                        """
                        SELECT id,operation_id,source_type,event_type,event_key,payload::text,attempts,generation
                        FROM platform_event_outbox
                        WHERE id=:id AND lease_token=:token AND status='PROCESSING' AND lease_until>clock_timestamp()
                        """)
                .param("id", claim.id())
                .param("token", claim.token())
                .query(
                        (rs, n) ->
                                new Event(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("operation_id", UUID.class),
                                        rs.getString("source_type"),
                                        rs.getString("event_type"),
                                        rs.getString("event_key"),
                                        rs.getString("payload"),
                                        rs.getInt("attempts"),
                                        rs.getInt("generation")))
                .optional();
    }

    /** Claims one due event. SKIP LOCKED gives horizontal workers exclusive ownership. */
    @Transactional
    public Optional<Claim> claim() {
        UUID token = UUID.randomUUID();
        return jdbc.sql(
                        """
                        WITH candidate AS (
                          SELECT id FROM platform_event_outbox
                          WHERE status='PENDING' AND available_at<=now() AND attempts<:max
                          ORDER BY available_at,created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
                        ) UPDATE platform_event_outbox e
                        SET status='PROCESSING',attempts=attempts+1,lease_token=:token,
                            lease_until=clock_timestamp()+interval '2 minutes',updated_at=now()
                        FROM candidate c WHERE e.id=c.id
                        RETURNING e.id,e.lease_token AS token,e.attempts,e.generation,e.operation_id
                        """)
                .param("max", MAX_ATTEMPTS)
                .param("token", token)
                .query(
                        (rs, n) ->
                                new Claim(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("token", UUID.class),
                                        rs.getInt("attempts"),
                                        rs.getInt("generation"),
                                        rs.getObject("operation_id", UUID.class)))
                .optional()
                .map(
                        claim -> {
                            insertHistory(
                                    claim.id(),
                                    null,
                                    claim.generation(),
                                    claim.attempts(),
                                    "CLAIMED",
                                    "PROCESSING",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null);
                            return claim;
                        });
    }

    @Transactional
    public boolean deliver(Claim claim) {
        return deliver(claim, DurableEventConsumerPort.ConsumptionResult.unknown());
    }

    @Transactional
    public boolean deliver(Claim claim, DurableEventConsumerPort.ConsumptionResult result) {
        Objects.requireNonNull(result);
        var delivery =
                jdbc.sql(
                                """
                                UPDATE platform_event_outbox SET status='DELIVERED',delivered_at=now(),
                                  lease_token=NULL,lease_until=NULL,last_error=NULL,updated_at=now()
                                WHERE id=:id AND lease_token=:token AND status='PROCESSING' AND lease_until>clock_timestamp()
                                RETURNING id,operation_id,generation,attempts
                                """)
                        .param("id", claim.id())
                        .param("token", claim.token())
                        .query(
                                (rs, n) ->
                                        new Object[] {
                                            rs.getObject("id", UUID.class),
                                            rs.getObject("operation_id", UUID.class),
                                            rs.getInt("generation"),
                                            rs.getInt("attempts")
                                        })
                        .optional();
        if (delivery.isEmpty()) return false;
        Object[] row = delivery.get();
        UUID eventId = (UUID) row[0];
        UUID operationId = (UUID) row[1];
        int generation = (int) row[2];
        int attempts = (int) row[3];
        insertHistory(
                eventId,
                null,
                generation,
                attempts,
                "SUCCEEDED",
                "DELIVERED",
                null,
                null,
                result,
                null,
                null);
        if (operationId != null) recordOperationDelivery(operationId, result);
        return true;
    }

    private void recordOperationDelivery(
            UUID operationId, DurableEventConsumerPort.ConsumptionResult result) {
        String skippedJson = mapper.writeValueAsString(result.skippedByReason());
        jdbc.sql(
                        """
                        WITH next_result AS (
                          SELECT o.operation_id,
                            o.completed_event_count+1 AS completed_count,
                            o.known_event_count+CASE WHEN :known THEN 1 ELSE 0 END AS known_count,
                            o.inbox_created+CASE WHEN :known THEN :created ELSE 0 END AS created_count,
                            o.inbox_skipped+CASE WHEN :known THEN :skipped ELSE 0 END AS skipped_count,
                            CASE WHEN :known THEN (
                              SELECT COALESCE(jsonb_object_agg(key,total),'{}'::jsonb) FROM (
                                SELECT key,sum(value::integer)::integer AS total FROM (
                                  SELECT key,value FROM jsonb_each_text(o.skipped_by_reason)
                                  UNION ALL
                                  SELECT key,value FROM jsonb_each_text(CAST(:skipReasons AS jsonb))
                                ) reason_rows GROUP BY key
                              ) totals
                            ) ELSE o.skipped_by_reason END AS reasons
                          FROM platform_event_operation o WHERE o.operation_id=:operationId
                        )
                        UPDATE platform_event_operation o SET
                          completed_event_count=n.completed_count,
                          known_event_count=n.known_count,
                          result_known=n.completed_count>=o.expected_event_count
                            AND n.known_count=o.expected_event_count,
                          inbox_created=n.created_count,
                          inbox_skipped=n.skipped_count,
                          skipped_by_reason=n.reasons,
                          outcome_code=CASE
                            WHEN EXISTS (SELECT 1 FROM platform_event_outbox e
                              WHERE e.operation_id=o.operation_id AND e.status='DEAD_LETTER') THEN 'DEAD_LETTER'
                            WHEN EXISTS (SELECT 1 FROM platform_event_outbox e
                              WHERE e.operation_id=o.operation_id AND e.status='FAILED') THEN 'FAILED'
                            WHEN EXISTS (SELECT 1 FROM platform_event_outbox e
                              WHERE e.operation_id=o.operation_id AND e.status='PENDING' AND e.last_error='NO_CONSUMER') THEN 'NO_CONSUMER'
                            WHEN EXISTS (SELECT 1 FROM platform_event_outbox e
                              WHERE e.operation_id=o.operation_id AND e.status='PENDING'
                                AND e.last_error IN ('CONSUMER_FAILED','LEASE_EXPIRED')) THEN 'RETRY_PENDING'
                            WHEN EXISTS (SELECT 1 FROM platform_event_outbox e
                              WHERE e.operation_id=o.operation_id AND e.status IN ('PENDING','PROCESSING')) THEN 'EVENTS_PENDING'
                            WHEN n.created_count>0 AND n.skipped_count>0 THEN 'PARTIAL'
                            WHEN n.created_count>0 THEN 'DELIVERED'
                            WHEN n.skipped_count>0
                              AND n.reasons=jsonb_build_object('EXPIRED', n.skipped_count)
                              THEN 'EXPIRED_ALL'
                            WHEN n.skipped_count>0
                              AND n.reasons=jsonb_build_object('DUPLICATE', n.skipped_count)
                              THEN 'DUPLICATE_ONLY'
                            WHEN n.skipped_count>0 THEN 'ALL_SKIPPED'
                            ELSE 'DELIVERED'
                          END,
                          completed_at=CASE WHEN n.completed_count>=o.expected_event_count
                            THEN now() ELSE o.completed_at END,
                          updated_at=now()
                        FROM next_result n WHERE o.operation_id=n.operation_id
                        """)
                .param("known", result.known())
                .param("created", result.inboxCreated())
                .param("skipped", result.inboxSkipped())
                .param("skipReasons", skippedJson)
                .param("operationId", operationId)
                .update();
    }

    private void insertHistory(
            UUID eventId,
            UUID auditOperationId,
            int generation,
            int attempt,
            String phase,
            String status,
            OffsetDateTime nextRetryAt,
            String errorCode,
            DurableEventConsumerPort.ConsumptionResult result,
            String actor,
            String reason) {
        boolean resultKnown = result != null && result.known();
        String skippedJson =
                resultKnown ? mapper.writeValueAsString(result.skippedByReason()) : "{}";
        jdbc.sql(
                        """
                        INSERT INTO platform_event_trace(
                          event_id,audit_operation_id,generation,attempt,phase,status,next_retry_at,
                          error_code,result_code,expected_count,inbox_created,inbox_skipped,
                          skipped_by_reason,actor,reason)
                        VALUES(:event,:auditId,:generation,:attempt,:phase,:status,:nextRetry,
                          :error,:resultCode,:expected,:created,:skipped,CAST(:skipReasons AS jsonb),
                          :actor,:reason)
                        """)
                .param("event", eventId)
                .param("auditId", auditOperationId)
                .param("generation", generation)
                .param("attempt", attempt)
                .param("phase", phase)
                .param("status", status)
                .param("nextRetry", nextRetryAt)
                .param("error", safeErrorCode(errorCode))
                .param("resultCode", resultKnown ? safeErrorCode(result.resultCode()) : null)
                .param("expected", resultKnown ? result.expectedCount() : null)
                .param("created", resultKnown ? result.inboxCreated() : null)
                .param("skipped", resultKnown ? result.inboxSkipped() : null)
                .param("skipReasons", skippedJson)
                .param("actor", actor)
                .param("reason", safeReason(reason))
                .update();
    }

    private static String safeErrorCode(String value) {
        if (value == null || value.isBlank()) return null;
        String code = value.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_:-]", "_");
        return code.substring(0, Math.min(64, code.length()));
    }

    private static String safeReason(String value) {
        if (value == null || value.isBlank()) return null;
        String reason = value.strip();
        return reason.substring(0, Math.min(1000, reason.length()));
    }

    private UpdatedState updatedState(ResultSet rs, int row) throws SQLException {
        return new UpdatedState(
                rs.getObject("id", UUID.class),
                rs.getObject("operation_id", UUID.class),
                rs.getInt("attempts"),
                rs.getInt("generation"),
                rs.getString("status"),
                rs.getObject("available_at", OffsetDateTime.class));
    }

    /** Retry with bounded exponential backoff; after eight attempts the event is a dead letter. */
    @Transactional
    public boolean fail(Claim claim, String error) {
        String safe = error == null || error.isBlank() ? "UNSPECIFIED" : safeErrorCode(error);
        var updated =
                jdbc.sql(
                                """
                                UPDATE platform_event_outbox SET
                                  status=CASE WHEN attempts>=:max THEN 'DEAD_LETTER' ELSE 'PENDING' END,
                                  available_at=CASE WHEN attempts>=:max THEN available_at ELSE now()+least(960,60*power(2,attempts-1))*interval '1 second' END,
                                  last_error=:error,lease_token=NULL,lease_until=NULL,updated_at=now()
                                WHERE id=:id AND lease_token=:token AND status='PROCESSING'
                                  AND lease_until>clock_timestamp()
                                RETURNING id,operation_id,attempts,generation,status,available_at
                                """)
                        .param("max", MAX_ATTEMPTS)
                        .param("error", safe)
                        .param("id", claim.id())
                        .param("token", claim.token())
                        .query(this::updatedState)
                        .optional();
        if (updated.isEmpty()) return false;
        UpdatedState state = updated.get();
        insertHistory(
                state.eventId(),
                null,
                state.generation(),
                state.attempts(),
                "FAILED",
                state.status(),
                "PENDING".equals(state.status()) ? state.availableAt() : null,
                safe,
                null,
                null,
                null);
        if (state.operationId() != null)
            updateOperationOutcome(
                    state.operationId(),
                    "DEAD_LETTER".equals(state.status()) ? "DEAD_LETTER" : "RETRY_PENDING");
        return true;
    }

    /**
     * Releases a claim when its event type has no registered consumer. This is not a handler
     * attempt: keep it pending and observable, retain a bounded retry delay and do not consume the
     * finite failure budget.
     */
    @Transactional
    public boolean releaseWithoutConsumer(Claim claim) {
        var updated =
                jdbc.sql(
                                """
                                UPDATE platform_event_outbox SET status='PENDING',attempts=GREATEST(0,attempts-1),
                                  available_at=now()+interval '5 minutes',last_error='NO_CONSUMER',
                                  lease_token=NULL,lease_until=NULL,updated_at=now()
                                WHERE id=:id AND lease_token=:token AND status='PROCESSING' AND lease_until>clock_timestamp()
                                RETURNING id,operation_id,attempts,generation,status,available_at
                                """)
                        .param("id", claim.id())
                        .param("token", claim.token())
                        .query(this::updatedState)
                        .optional();
        if (updated.isEmpty()) return false;
        UpdatedState state = updated.get();
        insertHistory(
                state.eventId(),
                null,
                state.generation(),
                state.attempts(),
                "NO_CONSUMER",
                state.status(),
                state.availableAt(),
                "NO_CONSUMER",
                null,
                null,
                null);
        if (state.operationId() != null) updateOperationOutcome(state.operationId(), "NO_CONSUMER");
        return true;
    }

    /** Converts abandoned leases to retryable or dead-letter records. */
    @Transactional
    public List<Recovery> recoverExpired() {
        var recovered =
                jdbc.sql(
                                """
                                WITH expired AS (
                                  SELECT id FROM platform_event_outbox
                                  WHERE status='PROCESSING' AND lease_until<=clock_timestamp()
                                  ORDER BY updated_at,id FOR UPDATE SKIP LOCKED LIMIT 1000
                                ) UPDATE platform_event_outbox e SET
                                  status=CASE WHEN attempts>=:max THEN 'DEAD_LETTER' ELSE 'PENDING' END,
                                  available_at=CASE WHEN attempts>=:max THEN available_at ELSE now() END,
                                  last_error='LEASE_EXPIRED',lease_token=NULL,lease_until=NULL,updated_at=now()
                                FROM expired x WHERE e.id=x.id
                                RETURNING e.id,e.status,e.attempts,e.generation,e.operation_id,e.available_at
                                """)
                        .param("max", MAX_ATTEMPTS)
                        .query(
                                (rs, n) ->
                                        new Recovery(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("status"),
                                                rs.getInt("attempts"),
                                                rs.getInt("generation"),
                                                rs.getObject("operation_id", UUID.class),
                                                rs.getObject("available_at", OffsetDateTime.class)))
                        .list();
        for (Recovery recovery : recovered) {
            insertHistory(
                    recovery.id(),
                    null,
                    recovery.generation(),
                    recovery.attempts(),
                    "LEASE_RECOVERED",
                    recovery.status(),
                    "PENDING".equals(recovery.status()) ? recovery.availableAt() : null,
                    "LEASE_EXPIRED",
                    null,
                    null,
                    null);
            if (recovery.operationId() != null && "DEAD_LETTER".equals(recovery.status()))
                updateOperationOutcome(recovery.operationId(), "DEAD_LETTER");
        }
        return recovered;
    }

    public long count(String status) {
        return jdbc.sql(
                        "SELECT count(*) FROM platform_event_outbox WHERE (:status='' OR"
                                + " status=:status)")
                .param("status", status == null ? "" : status)
                .query(Long.class)
                .single();
    }

    /**
     * Reset one dead letter after the administrative adapter has approved a replay. The row lock
     * prevents a concurrent maintenance worker from claiming the record between the status check
     * and reset.
     */
    @Transactional
    public Replay replay(UUID id) {
        return replay(id, null, null, null);
    }

    @Transactional
    public Replay replay(UUID id, String actor, String reason, UUID auditOperationId) {
        var current =
                jdbc.sql("SELECT status FROM platform_event_outbox WHERE id=:id FOR UPDATE")
                        .param("id", id)
                        .query(String.class)
                        .optional()
                        .orElseThrow(
                                () ->
                                        new org.springframework.web.server.ResponseStatusException(
                                                org.springframework.http.HttpStatus.NOT_FOUND,
                                                "eventNotFound"));
        if (!"DEAD_LETTER".equals(current)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "eventNotDeadLetter");
        }
        ReplayState replay =
                jdbc.sql(
                                """
                                UPDATE platform_event_outbox SET status='PENDING',attempts=0,
                                  available_at=now(),last_error=NULL,delivered_at=NULL,
                                  lease_token=NULL,lease_until=NULL,generation=generation+1,updated_at=now()
                                WHERE id=:id AND status='DEAD_LETTER'
                                RETURNING id,operation_id,attempts,generation
                                """)
                        .param("id", id)
                        .query(
                                (rs, n) ->
                                        new ReplayState(
                                                rs.getObject("id", UUID.class),
                                                rs.getObject("operation_id", UUID.class),
                                                rs.getInt("attempts"),
                                                rs.getInt("generation")))
                        .optional()
                        .orElseThrow(
                                () ->
                                        new org.springframework.web.server.ResponseStatusException(
                                                org.springframework.http.HttpStatus.CONFLICT,
                                                "eventNotDeadLetter"));
        insertHistory(
                id,
                auditOperationId,
                replay.generation(),
                replay.attempts(),
                "REPLAY_APPROVED",
                "PENDING",
                null,
                null,
                null,
                actor,
                reason);
        if (replay.operationId() != null)
            updateOperationOutcome(replay.operationId(), "EVENTS_PENDING");
        return new Replay(id, "PENDING", replay.attempts(), replay.generation());
    }

    /**
     * Delete only delivered records after the retention window. Pending, processing and dead-letter
     * events stay available for recovery or manual replay approval. The bounded CTE keeps cleanup
     * short under load.
     */
    @Transactional
    public int purgeDelivered(Duration retention, int batchSize) {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("Retention must be positive");
        }
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("Batch size must be between 1 and 10000");
        }
        var cutoff = OffsetDateTime.now().minus(retention);
        int purged =
                jdbc.sql(
                                """
                                WITH expired AS (
                                  SELECT e.id,e.operation_id FROM platform_event_outbox e
                                  WHERE e.status='DELIVERED' AND e.updated_at<:cutoff
                                    AND NOT EXISTS (
                                      SELECT 1 FROM platform_event_replay_approval a WHERE a.event_id=e.id
                                    )
                                  ORDER BY e.updated_at,e.id FOR UPDATE OF e SKIP LOCKED LIMIT :limit
                                ), deleted AS (
                                  DELETE FROM platform_event_outbox e USING expired x WHERE e.id=x.id
                                  RETURNING e.id,e.operation_id
                                ), operation_counts AS (
                                  SELECT operation_id,count(*)::integer AS expired_count
                                  FROM deleted WHERE operation_id IS NOT NULL GROUP BY operation_id
                                ), updated_operations AS (
                                  UPDATE platform_event_operation o SET
                                    expired_event_count=o.expired_event_count+c.expired_count,
                                    history_status=CASE
                                      WHEN o.expired_event_count+c.expired_count>=o.expected_event_count
                                        THEN 'EXPIRED' ELSE 'PARTIAL' END,
                                    updated_at=now()
                                  FROM operation_counts c WHERE o.operation_id=c.operation_id
                                  RETURNING o.operation_id
                                ) SELECT count(*) FROM deleted
                                """)
                        .param("cutoff", cutoff)
                        .param("limit", batchSize)
                        .query(Integer.class)
                        .single();
        // Operation summaries follow the audit retention window. Removing the summary detaches
        // any retained pending/dead-letter events (ON DELETE SET NULL) without deleting their
        // payload or replay approvals.
        jdbc.sql(
                        """
                        DELETE FROM platform_event_operation o WHERE o.operation_id IN (
                          SELECT candidate.operation_id FROM platform_event_operation candidate
                          WHERE (candidate.audit_operation_id IS NULL OR NOT EXISTS (
                            SELECT 1 FROM platform_audit a WHERE a.id=candidate.audit_operation_id
                          ))
                          ORDER BY candidate.created_at,candidate.operation_id
                          LIMIT :limit
                        )
                        """)
                .param("limit", batchSize)
                .update();
        return purged;
    }

    private String normalize(String sourceType, String eventType, String eventKey, String payload) {
        if (sourceType == null || !sourceType.matches("[a-z][a-z0-9._:-]{0,63}"))
            throw new IllegalArgumentException("Invalid event source");
        if (eventType == null || !eventType.matches("[a-z][a-z0-9._:-]{0,127}"))
            throw new IllegalArgumentException("Invalid event type");
        if (eventKey == null
                || eventKey.isBlank()
                || eventKey.length() > 191
                || eventKey.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid event key");
        if (payload == null) throw new IllegalArgumentException("Invalid event payload");
        final tools.jackson.databind.JsonNode node;
        try {
            node = mapper.readTree(payload);
        } catch (RuntimeException invalidJson) {
            throw new IllegalArgumentException("Invalid event payload");
        }
        if (node == null || node.isNull())
            throw new IllegalArgumentException("Invalid event payload");
        byte[] bytes = mapper.writeValueAsBytes(node);
        try {
            if (bytes.length < 2 || bytes.length > 262144)
                throw new IllegalArgumentException("Event payload too large");
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
