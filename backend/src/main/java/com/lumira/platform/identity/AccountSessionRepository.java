package com.lumira.platform.identity;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageFilterSql;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountSessionRepository {
    public record Session(String id,Instant createdAt,Instant lastAccessAt,Instant expiresAt,boolean current) {}
    private final JdbcClient jdbc;
    public AccountSessionRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    private PageFilterSql.Clause filters(PageRequest page) {
        return PageFilterSql.build(page, Map.of(
            "current", "session_id=:current",
            "createdAt", "to_timestamp(creation_time / 1000.0)",
            "lastAccessAt", "to_timestamp(last_access_time / 1000.0)",
            "expiresAt", "to_timestamp(expiry_time / 1000.0)"));
    }
    public long count(String username,String currentId,long now,PageRequest page) {
        var filter=filters(page);
        var parameters=new HashMap<String,Object>();parameters.put("name",username);parameters.put("now",now);parameters.put("current",currentId);parameters.putAll(filter.parameters());
        return jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name=:name AND expiry_time>:now"+filter.sql()).params(parameters).query(Long.class).single();
    }
    public List<Session> list(String username,String currentId,long now,PageRequest p) {
        var filter=filters(p);
        var parameters=new HashMap<String,Object>();parameters.put("name",username);parameters.put("current",currentId);parameters.put("now",now);parameters.put("limit",p.pageSize());parameters.put("offset",p.offset());parameters.putAll(filter.parameters());
        return jdbc.sql("SELECT primary_id,creation_time,last_access_time,expiry_time,session_id=:current AS current FROM spring_session WHERE principal_name=:name AND expiry_time>:now"+filter.sql()+" ORDER BY (session_id=:current) DESC,last_access_time DESC,primary_id LIMIT :limit OFFSET :offset")
            .params(parameters)
            .query((rs,n)->new Session(rs.getString("primary_id"),Instant.ofEpochMilli(rs.getLong("creation_time")),Instant.ofEpochMilli(rs.getLong("last_access_time")),Instant.ofEpochMilli(rs.getLong("expiry_time")),rs.getBoolean("current"))).list();
    }
    public boolean delete(String username,UUID id) {
        return jdbc.sql("DELETE FROM spring_session WHERE primary_id=:id AND principal_name=:name").param("id",id.toString()).param("name",username).update()>0;
    }
    public List<String> deleteOthers(String username,String currentId) {
        return jdbc.sql("DELETE FROM spring_session WHERE principal_name=:name AND session_id<>:current RETURNING primary_id").param("name",username).param("current",currentId).query(String.class).list();
    }
    public boolean current(String username,UUID id,String currentId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM spring_session WHERE primary_id=:id AND principal_name=:name AND session_id=:current)").param("id",id.toString()).param("name",username).param("current",currentId).query(Boolean.class).single();
    }
}
