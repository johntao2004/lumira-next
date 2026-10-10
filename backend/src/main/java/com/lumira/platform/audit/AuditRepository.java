package com.lumira.platform.audit;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageFilterSql;
import com.lumira.platform.common.PageRequest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository implements AuditPort {
    public record Event(
            UUID id, String actor, String action, UUID targetId, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private static final String FILTER =
            " WHERE (:search='' OR strpos(lower(actor),lower(:search))>0 OR strpos(lower(action),lower(:search))>0)";

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Call inside the same transaction as the state change being recorded. */
    public void append(String actor, String action, UUID targetId) {
        jdbc.sql(
                        "INSERT INTO platform_audit(id,actor,action,target_id) VALUES(:id,:actor,:action,:target)")
                .param("id", UUID.randomUUID())
                .param("actor", actor)
                .param("action", action)
                .param("target", targetId)
                .update();
    }

    private PageFilterSql.Clause filters(PageRequest page) {
        return PageFilterSql.build(
                page,
                Map.of(
                        "actor",
                        "actor",
                        "action",
                        "action",
                        "targetId",
                        "target_id",
                        "createdAt",
                        "created_at"));
    }

    public long count(PageRequest p) {
        var filter = filters(p);
        var params = new HashMap<String, Object>();
        params.put("search", p.search());
        params.putAll(filter.parameters());
        return jdbc.sql("SELECT count(*) FROM platform_audit" + FILTER + filter.sql())
                .params(params)
                .query(Long.class)
                .single();
    }

    public List<Event> list(PageRequest p) {
        var filter = filters(p);
        var params = new HashMap<String, Object>();
        params.put("search", p.search());
        params.put("limit", p.pageSize());
        params.put("offset", p.offset());
        params.putAll(filter.parameters());
        return jdbc.sql(
                        "SELECT id,actor,action,target_id,created_at FROM platform_audit"
                                + FILTER
                                + filter.sql()
                                + " ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset")
                .params(params)
                .query(
                        (rs, n) ->
                                new Event(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("actor"),
                                        rs.getString("action"),
                                        rs.getObject("target_id", UUID.class),
                                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    /** Delete one bounded batch so retention never holds a large table lock. */
    public int purgeBefore(OffsetDateTime cutoff, int batchSize) {
        if (cutoff == null || batchSize < 1 || batchSize > 5000)
            throw new IllegalArgumentException("Invalid audit retention batch");
        return jdbc.sql(
                        """
            WITH doomed AS (
              SELECT id FROM platform_audit
              WHERE created_at < :cutoff
              ORDER BY created_at,id
              LIMIT :limit
              FOR UPDATE SKIP LOCKED
            )
            DELETE FROM platform_audit a USING doomed d WHERE a.id=d.id
            """)
                .param("cutoff", cutoff)
                .param("limit", batchSize)
                .update();
    }
}
