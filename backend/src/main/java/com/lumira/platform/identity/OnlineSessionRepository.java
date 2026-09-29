package com.lumira.platform.identity;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageFilterSql;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read and revoke active sessions for the global online-user console. */
@Repository
public class OnlineSessionRepository {
    public record Entry(UUID id, UUID userId, String username, String displayName,
                         boolean enabled, Instant createdAt, Instant lastAccessAt, Instant expiresAt) {}
    private final JdbcClient jdbc;

    public OnlineSessionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private PageFilterSql.Clause filters(PageRequest request) {
        return PageFilterSql.build(request, Map.of(
            "username", "s.principal_name", "displayName", "u.display_name", "enabled", "u.enabled",
            "createdAt", "to_timestamp(s.creation_time / 1000.0)", "lastAccessAt", "to_timestamp(s.last_access_time / 1000.0)",
            "expiresAt", "to_timestamp(s.expiry_time / 1000.0)"));
    }

    public long count(PageRequest request, long now) {
        var filter=filters(request);var params=new HashMap<String,Object>();params.put("now",now);params.put("search",request.search());params.putAll(filter.parameters());
        return jdbc.sql("""
                SELECT count(*)
                FROM spring_session s JOIN platform_user u ON u.username=s.principal_name
                WHERE s.principal_name IS NOT NULL AND s.expiry_time>:now
                  AND (:search='' OR strpos(lower(s.principal_name),lower(:search))>0
                    OR strpos(lower(u.display_name),lower(:search))>0)
                """+filter.sql()).params(params).query(Long.class).single();
    }

    public List<Entry> list(PageRequest request, long now) {
        var filter=filters(request);var params=new HashMap<String,Object>();params.put("now",now);params.put("search",request.search());params.put("limit",request.pageSize());params.put("offset",request.offset());params.putAll(filter.parameters());
        return jdbc.sql("""
                SELECT trim(s.primary_id) AS id,u.id AS user_id,s.principal_name,u.display_name,u.enabled,
                       s.creation_time,s.last_access_time,s.expiry_time
                FROM spring_session s JOIN platform_user u ON u.username=s.principal_name
                WHERE s.principal_name IS NOT NULL AND s.expiry_time>:now
                  AND (:search='' OR strpos(lower(s.principal_name),lower(:search))>0
                    OR strpos(lower(u.display_name),lower(:search))>0)
                """+filter.sql()+" ORDER BY s.last_access_time DESC,s.primary_id LIMIT :limit OFFSET :offset")
            .params(params)
            .query((rs, n) -> new Entry(
                UUID.fromString(rs.getString("id")), rs.getObject("user_id", UUID.class),
                rs.getString("principal_name"), rs.getString("display_name"), rs.getBoolean("enabled"),
                Instant.ofEpochMilli(rs.getLong("creation_time")),
                Instant.ofEpochMilli(rs.getLong("last_access_time")),
                Instant.ofEpochMilli(rs.getLong("expiry_time")))).list();
    }

    public boolean revoke(UUID id) {
        return jdbc.sql("DELETE FROM spring_session WHERE trim(primary_id)=:id")
            .param("id", id.toString()).update() > 0;
    }

    public int revokeUser(UUID userId) {
        return jdbc.sql("""
                DELETE FROM spring_session s USING platform_user u
                WHERE u.id=:user AND s.principal_name=u.username
                RETURNING trim(s.primary_id)
                """).param("user", userId).query(String.class).list().size();
    }
}
