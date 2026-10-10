package com.lumira.platform.organizations;

import com.lumira.platform.common.AuthorizationContextProjectionPort;
import com.lumira.platform.common.PageFilterSql;
import com.lumira.platform.common.PageRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class OrganizationRepository {
    public record Organization(
            UUID id,
            UUID parentId,
            String code,
            String name,
            boolean enabled,
            int sortNo,
            long version) {
        public Organization(
                UUID id, UUID parentId, String code, String name, boolean enabled, long version) {
            this(id, parentId, code, name, enabled, 0, version);
        }
    }

    private final JdbcClient jdbc;
    private static final String SEARCH_FILTER =
            " AND (:search='' OR strpos(lower(code),lower(:search))>0 OR strpos(lower(name),lower(:search))>0)";

    public OrganizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private Organization row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Organization(
                rs.getObject("id", UUID.class),
                rs.getObject("parent_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("enabled"),
                rs.getInt("sort_no"),
                rs.getLong("version"));
    }

    private String parentFilter(boolean hierarchy, UUID parent) {
        return !hierarchy
                ? ""
                : parent == null ? " AND parent_id IS NULL" : " AND parent_id=:parent";
    }

    private String visibleOrganizations(AuthorizationContextProjectionPort.Snapshot context) {
        return context != null && context.previewActive()
                ? "platform_role_preview_visible_organizations(:actor,'platform:organizations:read',:targetRoleId,:targetRoleVersion)"
                : "platform_visible_organizations(:actor,'platform:organizations:read')";
    }

    private JdbcClient.StatementSpec filtered(
            String sql,
            String suffix,
            PageRequest p,
            boolean hierarchy,
            UUID parent,
            String actor,
            AuthorizationContextProjectionPort.Snapshot context) {
        var filters =
                PageFilterSql.build(
                        p, Map.of("code", "code", "name", "name", "enabled", "enabled"));
        var query =
                jdbc.sql(sql + filters.sql() + suffix)
                        .param("actor", actor)
                        .param("search", p.search())
                        .params(filters.parameters());
        if (context != null && context.previewActive())
            query.param("targetRoleId", context.targetRoleId())
                    .param("targetRoleVersion", context.targetRoleVersion());
        return hierarchy && parent != null ? query.param("parent", parent) : query;
    }

    public long count(PageRequest p, boolean hierarchy, UUID parent, String actor) {
        return count(p, hierarchy, parent, actor, null);
    }

    public long count(
            PageRequest p,
            boolean hierarchy,
            UUID parent,
            String actor,
            AuthorizationContextProjectionPort.Snapshot context) {
        return filtered(
                        "SELECT count(*) FROM platform_organization WHERE id IN (SELECT organization_id FROM "
                                + visibleOrganizations(context)
                                + ")"
                                + SEARCH_FILTER
                                + parentFilter(hierarchy, parent),
                        "",
                        p,
                        hierarchy,
                        parent,
                        actor,
                        context)
                .query(Long.class)
                .single();
    }

    public List<Organization> list(PageRequest p, boolean hierarchy, UUID parent, String actor) {
        return list(p, hierarchy, parent, actor, null);
    }

    public List<Organization> list(
            PageRequest p,
            boolean hierarchy,
            UUID parent,
            String actor,
            AuthorizationContextProjectionPort.Snapshot context) {
        return filtered(
                        "SELECT * FROM platform_organization WHERE id IN (SELECT organization_id FROM "
                                + visibleOrganizations(context)
                                + ")"
                                + SEARCH_FILTER
                                + parentFilter(hierarchy, parent),
                        " ORDER BY sort_no ASC,code ASC,id ASC LIMIT :limit OFFSET :offset",
                        p,
                        hierarchy,
                        parent,
                        actor,
                        context)
                .param("limit", p.pageSize())
                .param("offset", p.offset())
                .query((rs, n) -> row(rs))
                .list();
    }

    public List<Organization> ancestors(UUID id, String actor) {
        return jdbc.sql(
                        "WITH RECURSIVE ancestors AS (SELECT o.*,0 AS depth FROM platform_organization o WHERE id=:id UNION ALL SELECT o.*,a.depth+1 FROM platform_organization o JOIN ancestors a ON o.id=a.parent_id) SELECT a.* FROM ancestors a WHERE a.id IN (SELECT organization_id FROM platform_visible_organizations(:actor,'platform:organizations:read')) ORDER BY depth DESC")
                .param("id", id)
                .param("actor", actor)
                .query((rs, n) -> row(rs))
                .list();
    }

    public List<Organization> ancestors(
            UUID id, String actor, AuthorizationContextProjectionPort.Snapshot context) {
        var query =
                jdbc.sql(
                                "WITH RECURSIVE ancestors AS (SELECT o.*,0 AS depth FROM platform_organization o WHERE id=:id UNION ALL SELECT o.*,a.depth+1 FROM platform_organization o JOIN ancestors a ON o.id=a.parent_id) SELECT a.* FROM ancestors a WHERE a.id IN (SELECT organization_id FROM "
                                        + visibleOrganizations(context)
                                        + ") ORDER BY depth DESC")
                        .param("id", id)
                        .param("actor", actor);
        if (context != null && context.previewActive())
            query.param("targetRoleId", context.targetRoleId())
                    .param("targetRoleVersion", context.targetRoleVersion());
        return query.query((rs, n) -> row(rs)).list();
    }

    public Organization find(UUID id) {
        return jdbc.sql("SELECT * FROM platform_organization WHERE id=:id")
                .param("id", id)
                .query((rs, n) -> row(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public Organization findVisible(UUID id, String actor) {
        return jdbc.sql(
                        "SELECT * FROM platform_organization WHERE id=:id AND id IN (SELECT organization_id FROM platform_visible_organizations(:actor,'platform:organizations:read'))")
                .param("id", id)
                .param("actor", actor)
                .query((rs, n) -> row(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public Organization findVisible(
            UUID id, String actor, AuthorizationContextProjectionPort.Snapshot context) {
        var query =
                jdbc.sql(
                                "SELECT * FROM platform_organization WHERE id=:id AND id IN (SELECT organization_id FROM "
                                        + visibleOrganizations(context)
                                        + ")")
                        .param("id", id)
                        .param("actor", actor);
        if (context != null && context.previewActive())
            query.param("targetRoleId", context.targetRoleId())
                    .param("targetRoleVersion", context.targetRoleVersion());
        return query.query((rs, n) -> row(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public boolean cycles(UUID id, UUID parent) {
        return jdbc.sql(
                        "WITH RECURSIVE ancestors AS (SELECT id,parent_id FROM platform_organization WHERE id=:parent UNION SELECT o.id,o.parent_id FROM platform_organization o JOIN ancestors a ON o.id=a.parent_id) SELECT EXISTS(SELECT 1 FROM ancestors WHERE id=:id)")
                .param("id", id)
                .param("parent", parent)
                .query(Boolean.class)
                .single();
    }

    public void create(
            UUID id, UUID parent, String code, String name, boolean enabled, int sortNo) {
        jdbc.sql(
                        "INSERT INTO platform_organization(id,parent_id,code,name,enabled,sort_no) VALUES(:id,:parent,:code,:name,:enabled,:sortNo)")
                .param("id", id)
                .param("parent", parent)
                .param("code", code)
                .param("name", name)
                .param("enabled", enabled)
                .param("sortNo", sortNo)
                .update();
    }

    public boolean update(
            UUID id, UUID parent, String name, boolean enabled, int sortNo, long version) {
        return jdbc.sql(
                                "UPDATE platform_organization SET parent_id=:parent,name=:name,enabled=:enabled,sort_no=:sortNo,version=version+1 WHERE id=:id AND version=:version")
                        .param("id", id)
                        .param("parent", parent)
                        .param("name", name)
                        .param("enabled", enabled)
                        .param("sortNo", sortNo)
                        .param("version", version)
                        .update()
                == 1;
    }

    public List<UUID> memberships(UUID userId) {
        return jdbc.sql(
                        "SELECT organization_id FROM platform_user_organization WHERE user_id=:id ORDER BY organization_id")
                .param("id", userId)
                .query(UUID.class)
                .list();
    }

    public void memberships(UUID userId, Set<UUID> ids) {
        jdbc.sql("DELETE FROM platform_user_organization WHERE user_id=:id")
                .param("id", userId)
                .update();
        for (var id : ids)
            jdbc.sql(
                            "INSERT INTO platform_user_organization(user_id,organization_id) VALUES(:user,:org)")
                    .param("user", userId)
                    .param("org", id)
                    .update();
        jdbc.sql("UPDATE platform_user SET version=version+1 WHERE id=:id")
                .param("id", userId)
                .update();
    }

    public boolean inUse(UUID id) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_organization WHERE parent_id=:id) OR EXISTS(SELECT 1 FROM platform_user_organization WHERE organization_id=:id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public boolean delete(UUID id, long version) {
        return jdbc.sql("DELETE FROM platform_organization WHERE id=:id AND version=:version")
                        .param("id", id)
                        .param("version", version)
                        .update()
                == 1;
    }
}
