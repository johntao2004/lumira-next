package com.lumira.platform.sensitive;

import com.lumira.platform.common.PageRequest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.RowCallbackHandler;
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

    public record ActorPolicies(long revision, List<Policy> policies) {}

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
                        """
                        WITH actor AS NOT MATERIALIZED (
                            SELECT u.id
                              FROM platform_user u
                             WHERE u.username = :actor AND u.enabled AND u.deleted_at IS NULL
                        )
                        SELECT r.id, r.code, r.name, r.enabled,
                               coalesce(p.mode, 'ON') AS mode,
                               coalesce(p.version, 0) AS version
                          FROM actor a
                          JOIN platform_user_role ur ON ur.user_id = a.id
                          JOIN platform_role r ON r.id = ur.role_id AND r.enabled
                          LEFT JOIN platform_sensitive_role_policy p ON p.role_id = r.id
                         ORDER BY r.id
                        """)
                .param("actor", actor)
                .query((rs, n) -> row(rs))
                .list();
    }

    private static final String ACTOR_REVISION_SELECT =
            """
            WITH actor AS NOT MATERIALIZED (
                SELECT u.id
                  FROM platform_user u
                 WHERE u.username = :actor AND u.enabled AND u.deleted_at IS NULL
            )
            SELECT rev.revision, r.id, r.code, r.name, r.enabled, r.mode, r.version
              FROM platform_sensitive_word_revision rev
              LEFT JOIN (
                  SELECT r.id, r.code, r.name, r.enabled,
                         coalesce(p.mode, 'ON') AS mode,
                         coalesce(p.version, 0) AS version
                    FROM actor a
                    JOIN platform_user_role ur ON ur.user_id = a.id
                    JOIN platform_role r ON r.id = ur.role_id AND r.enabled
                    LEFT JOIN platform_sensitive_role_policy p ON p.role_id = r.id
              ) r ON true
             WHERE rev.singleton = true
             ORDER BY r.id
            """;

    public ActorPolicies forActorWithRevision(String actor) {
        try {
            Long[] rev = new Long[1];
            List<Policy> list = new ArrayList<>();
            jdbc.sql(ACTOR_REVISION_SELECT)
                    .param("actor", actor)
                    .query(
                            (RowCallbackHandler)
                                    rs -> {
                                        if (rev[0] == null) {
                                            rev[0] = rs.getLong("revision");
                                        }
                                        UUID id = rs.getObject("id", UUID.class);
                                        if (id != null) {
                                            list.add(row(rs));
                                        }
                                    });
            if (rev[0] == null) {
                return null;
            }
            return new ActorPolicies(rev[0], List.copyOf(list));
        } catch (org.springframework.dao.DataAccessException ex) {
            return null;
        }
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
                .param("actor", actor == null ? "" : actor)
                .param(
                        "source",
                        source == null
                                ? ""
                                : (source.length() > 300 ? source.substring(0, 300) : source))
                .param("action", blocked ? "BLOCK" : "LOG_ONLY")
                .param("count", count)
                .param(
                        "roles",
                        "{"
                                + (roleIds == null ? List.<UUID>of() : roleIds)
                                        .stream()
                                                .map(UUID::toString)
                                                .collect(java.util.stream.Collectors.joining(","))
                                + "}")
                .update();
    }

    private Hit hitRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Hit(
                rs.getObject("id", UUID.class),
                rs.getString("actor"),
                rs.getString("source"),
                rs.getString("action"),
                rs.getInt("match_count"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    public List<Hit> hits(PageRequest page) {
        return jdbc.sql(
                        "SELECT id,actor,source,action,match_count,created_at FROM platform_content_policy_hit WHERE strpos(lower(actor||' '||source),lower(:search))>0 ORDER BY created_at DESC,id LIMIT :limit OFFSET :offset")
                .param("search", page.search())
                .param("limit", page.pageSize())
                .param("offset", page.offset())
                .query((rs, n) -> hitRow(rs))
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
