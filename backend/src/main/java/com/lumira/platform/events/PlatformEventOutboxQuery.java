package com.lumira.platform.events;

import com.lumira.platform.common.*;
import com.lumira.platform.common.AdministrationPort;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Metadata-only operational view. Event payloads never leave the internal producer/consumer boundary. */
@Service
public class PlatformEventOutboxQuery {
    public record Entry(UUID id, String sourceType, String eventType, String eventKey, String status,
                        int attempts, String lastError, OffsetDateTime availableAt, OffsetDateTime deliveredAt,
                        OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    private static final Set<String> STATUSES = Set.of("PENDING", "PROCESSING", "DELIVERED", "FAILED", "DEAD_LETTER");
    private final JdbcClient jdbc;
    private final AdministrationPort guard;
    public PlatformEventOutboxQuery(JdbcClient jdbc, AdministrationPort guard) { this.jdbc = jdbc; this.guard = guard; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<Entry> list(String actor, PageRequest page, String status) {
        guard.requireGlobal(actor, "platform:audit:read");
        if (status == null || (!status.isEmpty() && !STATUSES.contains(status))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidEventStatus");
        String filter = " WHERE (:status='' OR status=:status) AND (:search='' OR strpos(lower(source_type),lower(:search))>0 OR strpos(lower(event_type),lower(:search))>0 OR strpos(lower(event_key),lower(:search))>0)";
        var fieldFilter=PageFilterSql.build(page,Map.of("sourceType","source_type","eventType","event_type","eventKey","event_key","status","status","attempts","attempts","lastError","last_error","createdAt","created_at","deliveredAt","delivered_at"));
        var countParams=new HashMap<String,Object>();countParams.put("status",status);countParams.put("search",page.search());countParams.putAll(fieldFilter.parameters());
        var rowsParams=new HashMap<>(countParams);rowsParams.put("limit",page.pageSize());rowsParams.put("offset",page.offset());
        var rows = jdbc.sql("SELECT id,source_type,event_type,event_key,status,attempts,last_error,available_at,delivered_at,created_at,updated_at FROM platform_event_outbox" + filter + fieldFilter.sql() + " ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset")
            .params(rowsParams).query(Entry.class).list();
        var count = jdbc.sql("SELECT count(*) FROM platform_event_outbox" + filter + fieldFilter.sql()).params(countParams).query(Long.class).single();
        return new PageResult<>(rows, count, page.page(), page.pageSize());
    }
}
