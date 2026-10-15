package com.lumira.platform.events;

import com.lumira.platform.common.*;
import com.lumira.platform.common.AdministrationPort;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Metadata-only operational view. Event payloads never leave the internal producer/consumer
 * boundary.
 */
@Service
public class PlatformEventOutboxQuery {
    public record Entry(
            UUID id,
            String sourceType,
            String eventType,
            String eventKey,
            String status,
            int attempts,
            String lastError,
            OffsetDateTime availableAt,
            OffsetDateTime deliveredAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    private static final Set<String> STATUSES =
            Set.of("PENDING", "PROCESSING", "DELIVERED", "FAILED", "DEAD_LETTER");
    private final JdbcClient jdbc;
    private final AdministrationPort guard;

    public PlatformEventOutboxQuery(JdbcClient jdbc, AdministrationPort guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<Entry> list(String actor, PageRequest page, String status) {
        guard.requirePageGlobal(actor, PlatformPageId.EVENT_OUTBOX);
        if (status == null || (!status.isEmpty() && !STATUSES.contains(status)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidEventStatus");
        String filter =
                " WHERE (:status='' OR status=:status) AND (:search='' OR"
                        + " strpos(lower(source_type),lower(:search))>0 OR"
                        + " strpos(lower(event_type),lower(:search))>0 OR"
                        + " strpos(lower(event_key),lower(:search))>0)";
        var fieldFilter =
                PageFilterSql.build(
                        page,
                        Map.of(
                                "sourceType",
                                "source_type",
                                "eventType",
                                "event_type",
                                "eventKey",
                                "event_key",
                                "status",
                                "status",
                                "attempts",
                                "attempts",
                                "lastError",
                                "last_error",
                                "createdAt",
                                "created_at",
                                "deliveredAt",
                                "delivered_at"),
                        java.util.Set.of("createdAt", "deliveredAt"));
        var countParams = new HashMap<String, Object>();
        countParams.put("status", status);
        countParams.put("search", page.search());
        countParams.putAll(fieldFilter.parameters());
        var rowsParams = new HashMap<>(countParams);
        rowsParams.put("limit", page.pageSize());
        rowsParams.put("offset", page.offset());
        var rows =
                jdbc.sql(
                                "SELECT"
                                    + " id,source_type,event_type,event_key,status,attempts,last_error,available_at,delivered_at,created_at,updated_at"
                                    + " FROM platform_event_outbox"
                                        + filter
                                        + fieldFilter.sql()
                                        + " ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET"
                                        + " :offset")
                        .params(rowsParams)
                        .query(
                                (rs, n) ->
                                        new Entry(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("source_type"),
                                                rs.getString("event_type"),
                                                rs.getString("event_key"),
                                                rs.getString("status"),
                                                rs.getInt("attempts"),
                                                safeErrorCode(rs.getString("last_error")),
                                                rs.getObject("available_at", OffsetDateTime.class),
                                                rs.getObject("delivered_at", OffsetDateTime.class),
                                                rs.getObject("created_at", OffsetDateTime.class),
                                                rs.getObject("updated_at", OffsetDateTime.class)))
                        .list();
        var count =
                jdbc.sql("SELECT count(*) FROM platform_event_outbox" + filter + fieldFilter.sql())
                        .params(countParams)
                        .query(Long.class)
                        .single();
        return new PageResult<>(rows, count, page.page(), page.pageSize());
    }

    private static String safeErrorCode(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "CONSUMER_FAILED", "NO_CONSUMER", "LEASE_EXPIRED", "UNSPECIFIED" ->
                    value.strip().toUpperCase(Locale.ROOT);
            default -> "OTHER_ERROR";
        };
    }
}
