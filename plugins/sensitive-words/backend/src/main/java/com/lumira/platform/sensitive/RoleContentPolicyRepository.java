package com.lumira.platform.sensitive;

import com.lumira.platform.common.PageRequest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class RoleContentPolicyRepository {
    public record Policy(
            UUID roleId,
            String roleCode,
            String roleName,
            boolean roleEnabled,
            String mode,
            long version) {}

    public record Hit(
            UUID id,
            String actor,
            String source,
            String action,
            int matchCount,
            OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;

    public RoleContentPolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT =
            "SELECT r.id,r.code,r.name,r.enabled,coalesce(p.mode,'ON') mode,coalesce(p.version,0) version FROM platform_role r LEFT JOIN platform_sensitive_role_policy p ON p.role_id=r.id";

    private Policy row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Policy(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("enabled"),
                rs.getString("mode"),
                rs.getLong("version"));
    }

    public List<Policy> list(PageRequest page) {
        return jdbc.sql(
                        SELECT
                                + " WHERE strpos(lower(r.name||' '||r.code),lower(:search))>0 ORDER BY r.name,r.id LIMIT :limit OFFSET :offset")
                .param("search", page.search())
                .param("limit", page.pageSize())
                .param("offset", page.offset())
                .query((rs, n) -> row(rs))
                .list();
    }

    public long count(PageRequest page) {
        return jdbc.sql(
                        "SELECT count(*) FROM platform_role WHERE strpos(lower(name||' '||code),lower(:search))>0")
                .param("search", page.search())
                .query(Long.class)
                .single();
    }

    public Optional<Policy> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id=:id")
                .param("id", id)
                .query((rs, n) -> row(rs))
                .optional();
    }

    public List<Policy> forActor(String actor) {
        return jdbc.sql(
                        SELECT
                                + " JOIN platform_user_role ur ON ur.role_id=r.id JOIN platform_user u ON u.id=ur.user_id WHERE u.username=:actor AND u.enabled AND u.deleted_at IS NULL AND r.enabled ORDER BY r.id")
                .param("actor", actor)
                .query((rs, n) -> row(rs))
                .list();
    }

    public boolean save(UUID id, String mode, long version, String actor) {
        if (version == 0) {
            return jdbc.sql(
                                    "INSERT INTO platform_sensitive_role_policy(role_id,mode,updated_by) VALUES(:id,:mode,:actor) ON CONFLICT DO NOTHING")
                            .param("id", id)
                            .param("mode", mode)
                            .param("actor", actor)
                            .update()
                    == 1;
        }
        return jdbc.sql(
                                "UPDATE platform_sensitive_role_policy SET mode=:mode,updated_by=:actor,updated_at=clock_timestamp(),version=version+1 WHERE role_id=:id AND version=:version")
                        .param("id", id)
                        .param("mode", mode)
                        .param("actor", actor)
                        .param("version", version)
                        .update()
                == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            String actor, String source, boolean blocked, int count, List<UUID> roleIds) {
        jdbc.sql(
                        "INSERT INTO platform_content_policy_hit(id,actor,source,action,match_count,role_ids) VALUES(:id,:actor,:source,:action,:count,CAST(:roles AS uuid[]))")
                .param("id", UUID.randomUUID())
                .param("actor", actor)
                .param("source", source.length() > 300 ? source.substring(0, 300) : source)
                .param("action", blocked ? "BLOCK" : "LOG_ONLY")
                .param("count", count)
                .param(
                        "roles",
                        "{"
                                + roleIds.stream()
                                        .map(UUID::toString)
                                        .collect(java.util.stream.Collectors.joining(","))
                                + "}")
                .update();
    }

    public List<Hit> hits(PageRequest page) {
        return jdbc.sql(
                        "SELECT id,actor,source,action,match_count,created_at FROM platform_content_policy_hit WHERE strpos(lower(actor||' '||source),lower(:search))>0 ORDER BY created_at DESC,id LIMIT :limit OFFSET :offset")
                .param("search", page.search())
                .param("limit", page.pageSize())
                .param("offset", page.offset())
                .query(Hit.class)
                .list();
    }

    public long hitCount(PageRequest page) {
        return jdbc.sql(
                        "SELECT count(*) FROM platform_content_policy_hit WHERE strpos(lower(actor||' '||source),lower(:search))>0")
                .param("search", page.search())
                .query(Long.class)
                .single();
    }
}
