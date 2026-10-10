package com.lumira.platform.events;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Durable, idempotent event boundary shared by platform modules and future business modules. */
@Repository
public class PlatformEventOutboxStore {
    public static final int MAX_ATTEMPTS = 8;

    public record Claim(UUID id, UUID token, int attempts) {}

    public record Event(
            UUID id,
            String sourceType,
            String eventType,
            String eventKey,
            String payload,
            int attempts) {}

    public record Recovery(UUID id, String status) {}

    public record Replay(UUID id, String status, int attempts) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PlatformEventOutboxStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** First write wins for the same source/event/key. Call from the producer transaction. */
    @Transactional
    public boolean append(
            UUID id, String sourceType, String eventType, String eventKey, String payload) {
        Objects.requireNonNull(id);
        String normalized = normalize(sourceType, eventType, eventKey, payload);
        return jdbc.sql(
                                """
                INSERT INTO platform_event_outbox(id,source_type,event_type,event_key,payload)
                VALUES(:id,:source,:type,:key,CAST(:payload AS jsonb))
                ON CONFLICT DO NOTHING
                """)
                        .param("id", id)
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
                SELECT id,source_type,event_type,event_key,payload::text,attempts
                FROM platform_event_outbox
                WHERE id=:id AND lease_token=:token AND status='PROCESSING' AND lease_until>now()
                """)
                .param("id", claim.id())
                .param("token", claim.token())
                .query(
                        (rs, n) ->
                                new Event(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("source_type"),
                                        rs.getString("event_type"),
                                        rs.getString("event_key"),
                                        rs.getString("payload"),
                                        rs.getInt("attempts")))
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
                lease_until=now()+interval '2 minutes',updated_at=now()
            FROM candidate c WHERE e.id=c.id
            RETURNING e.id,e.lease_token AS token,e.attempts
            """)
                .param("max", MAX_ATTEMPTS)
                .param("token", token)
                .query(Claim.class)
                .optional();
    }

    @Transactional
    public boolean deliver(Claim claim) {
        return jdbc.sql(
                                """
            UPDATE platform_event_outbox SET status='DELIVERED',delivered_at=now(),
              lease_token=NULL,lease_until=NULL,last_error=NULL,updated_at=now()
            WHERE id=:id AND lease_token=:token AND status='PROCESSING' AND lease_until>now()
            """)
                        .param("id", claim.id())
                        .param("token", claim.token())
                        .update()
                == 1;
    }

    /** Retry with bounded exponential backoff; after eight attempts the event is a dead letter. */
    @Transactional
    public boolean fail(Claim claim, String error) {
        String safe =
                error == null || error.isBlank()
                        ? "UNSPECIFIED"
                        : error.substring(0, Math.min(1000, error.length()));
        return jdbc.sql(
                                """
            UPDATE platform_event_outbox SET
              status=CASE WHEN attempts>=:max THEN 'DEAD_LETTER' ELSE 'PENDING' END,
              available_at=CASE WHEN attempts>=:max THEN available_at ELSE now()+least(960,60*power(2,attempts-1))*interval '1 second' END,
              last_error=:error,lease_token=NULL,lease_until=NULL,updated_at=now()
            WHERE id=:id AND lease_token=:token AND status='PROCESSING'
            """)
                        .param("max", MAX_ATTEMPTS)
                        .param("error", safe)
                        .param("id", claim.id())
                        .param("token", claim.token())
                        .update()
                == 1;
    }

    /** Converts abandoned leases to retryable or dead-letter records. */
    @Transactional
    public List<Recovery> recoverExpired() {
        return jdbc.sql(
                        """
            WITH expired AS (
              SELECT id FROM platform_event_outbox
              WHERE status='PROCESSING' AND lease_until<=now()
              ORDER BY updated_at,id FOR UPDATE SKIP LOCKED LIMIT 1000
            ) UPDATE platform_event_outbox e SET
              status=CASE WHEN attempts>=:max THEN 'DEAD_LETTER' ELSE 'PENDING' END,
              available_at=CASE WHEN attempts>=:max THEN available_at ELSE now() END,
              last_error='LEASE_EXPIRED',lease_token=NULL,lease_until=NULL,updated_at=now()
            FROM expired x WHERE e.id=x.id
            RETURNING e.id,e.status
            """)
                .param("max", MAX_ATTEMPTS)
                .query(Recovery.class)
                .list();
    }

    public long count(String status) {
        return jdbc.sql(
                        "SELECT count(*) FROM platform_event_outbox WHERE (:status='' OR status=:status)")
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
        jdbc.sql(
                        """
            UPDATE platform_event_outbox SET status='PENDING',attempts=0,
              available_at=now(),last_error=NULL,delivered_at=NULL,
              lease_token=NULL,lease_until=NULL,updated_at=now()
            WHERE id=:id AND status='DEAD_LETTER'
            """)
                .param("id", id)
                .update();
        return new Replay(id, "PENDING", 0);
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
        return jdbc.sql(
                        """
            WITH expired AS (
              SELECT e.id FROM platform_event_outbox e
              WHERE e.status='DELIVERED' AND e.updated_at<:cutoff
                AND NOT EXISTS (
                  SELECT 1 FROM platform_event_replay_approval a WHERE a.event_id=e.id
                )
              ORDER BY e.updated_at,e.id FOR UPDATE OF e SKIP LOCKED LIMIT :limit
            ) DELETE FROM platform_event_outbox e USING expired x WHERE e.id=x.id
            """)
                .param("cutoff", cutoff)
                .param("limit", batchSize)
                .update();
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
