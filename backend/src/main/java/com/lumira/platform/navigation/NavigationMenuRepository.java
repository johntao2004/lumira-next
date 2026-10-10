package com.lumira.platform.navigation;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/** JDBC persistence for the versioned platform navigation tree. */
@Repository
public class NavigationMenuRepository {
    public record Menu(
            UUID id,
            UUID parentId,
            String menuCode,
            String labelKey,
            String labelDefault,
            String menuType,
            String sectionKey,
            String path,
            String componentKey,
            String icon,
            String permissionKey,
            boolean globalOnly,
            String moduleId,
            int sortOrder,
            boolean enabled,
            boolean builtIn,
            boolean deleted,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    private final JdbcClient jdbc;

    public NavigationMenuRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private Menu row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Menu(
                rs.getObject("id", UUID.class),
                rs.getObject("parent_id", UUID.class),
                rs.getString("menu_code"),
                rs.getString("label_key"),
                rs.getString("label_default"),
                rs.getString("menu_type"),
                rs.getString("section_key"),
                rs.getString("path"),
                rs.getString("component_key"),
                rs.getString("icon"),
                rs.getString("permission_key"),
                rs.getBoolean("global_only"),
                rs.getString("module_id"),
                rs.getInt("sort_order"),
                rs.getBoolean("enabled"),
                rs.getBoolean("built_in"),
                rs.getBoolean("deleted"),
                rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static final String SELECT =
            "SELECT id,parent_id,menu_code,label_key,label_default,menu_type,section_key,path,component_key,icon,permission_key,global_only,module_id,sort_order,enabled,built_in,deleted,version,created_at,updated_at FROM platform_navigation_menu";

    /** Serialize navigation writes so validation and the following mutation see one graph. */
    public void lockGraph() {
        jdbc.sql("SELECT pg_advisory_xact_lock(481028)")
                .query(
                        rs -> {
                            rs.next();
                            return 0;
                        });
    }

    public List<Menu> list(boolean includeDeleted) {
        return jdbc.sql(
                        SELECT
                                + " WHERE (:includeDeleted OR deleted=false) ORDER BY parent_id NULLS FIRST,sort_order,id")
                .param("includeDeleted", includeDeleted)
                .query((rs, n) -> row(rs))
                .list();
    }

    public Menu find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id=:id")
                .param("id", id)
                .query((rs, n) -> row(rs))
                .optional()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "navigationNotFound"));
    }

    public boolean exists(UUID id) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_navigation_menu WHERE id=:id AND deleted=false)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public boolean codeExists(String menuCode, UUID exceptId) {
        if (exceptId == null) {
            return jdbc.sql(
                            "SELECT EXISTS(SELECT 1 FROM platform_navigation_menu WHERE menu_code=:code AND deleted=false)")
                    .param("code", menuCode)
                    .query(Boolean.class)
                    .single();
        }
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_navigation_menu WHERE menu_code=:code AND deleted=false AND (:exceptId IS NULL OR id<>:exceptId))")
                .param("code", menuCode)
                .param("exceptId", exceptId)
                .query(Boolean.class)
                .single();
    }

    /** True when another live PAGE owns either address. */
    public boolean routeConflict(UUID exceptId, String sectionKey, String path) {
        if (sectionKey == null && path == null) return false;
        var exclusion = exceptId == null ? "" : " AND id<>:exceptId";
        var query =
                sectionKey != null && path != null
                        ? "SELECT EXISTS(SELECT 1 FROM platform_navigation_menu WHERE deleted=false AND menu_type='PAGE'"
                                + exclusion
                                + " AND (section_key=:sectionKey OR path=:path))"
                        : sectionKey != null
                                ? "SELECT EXISTS(SELECT 1 FROM platform_navigation_menu WHERE deleted=false AND menu_type='PAGE'"
                                        + exclusion
                                        + " AND section_key=:sectionKey)"
                                : "SELECT EXISTS(SELECT 1 FROM platform_navigation_menu WHERE deleted=false AND menu_type='PAGE'"
                                        + exclusion
                                        + " AND path=:path)";
        var statement = jdbc.sql(query);
        if (exceptId != null) statement = statement.param("exceptId", exceptId);
        if (sectionKey != null) statement = statement.param("sectionKey", sectionKey);
        if (path != null) statement = statement.param("path", path);
        return statement.query(Boolean.class).single();
    }

    public boolean hasChildren(UUID id) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_navigation_menu WHERE parent_id=:id AND deleted=false)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public boolean cycles(UUID id, UUID parentId) {
        if (parentId == null) return false;
        return jdbc.sql(
                        """
            WITH RECURSIVE ancestors AS (
              SELECT id,parent_id FROM platform_navigation_menu WHERE id=:parent
              UNION ALL
              SELECT n.id,n.parent_id FROM platform_navigation_menu n JOIN ancestors a ON n.id=a.parent_id
            ) SELECT EXISTS(SELECT 1 FROM ancestors WHERE id=:id)
            """)
                .param("id", id)
                .param("parent", parentId)
                .query(Boolean.class)
                .single();
    }

    public void create(
            UUID id,
            UUID parentId,
            String menuCode,
            String labelKey,
            String labelDefault,
            String menuType,
            String sectionKey,
            String path,
            String componentKey,
            String icon,
            String permissionKey,
            boolean globalOnly,
            String moduleId,
            int sortOrder,
            boolean enabled) {
        jdbc.sql(
                        """
            INSERT INTO platform_navigation_menu
              (id,parent_id,menu_code,label_key,label_default,menu_type,section_key,path,component_key,icon,permission_key,global_only,module_id,sort_order,enabled)
            VALUES (:id,:parent,:code,:labelKey,:labelDefault,:type,:section,:path,:component,:icon,:permission,:globalOnly,:module,:sort,:enabled)
            """)
                .param("id", id)
                .param("parent", parentId)
                .param("code", menuCode)
                .param("labelKey", labelKey)
                .param("labelDefault", labelDefault)
                .param("type", menuType)
                .param("section", sectionKey)
                .param("path", path)
                .param("component", componentKey)
                .param("icon", icon)
                .param("permission", permissionKey)
                .param("globalOnly", globalOnly)
                .param("module", moduleId)
                .param("sort", sortOrder)
                .param("enabled", enabled)
                .update();
    }

    public int update(
            UUID id,
            long version,
            UUID parentId,
            String labelKey,
            String labelDefault,
            String menuType,
            String sectionKey,
            String path,
            String componentKey,
            String icon,
            String permissionKey,
            boolean globalOnly,
            String moduleId,
            int sortOrder,
            boolean enabled) {
        return jdbc.sql(
                        """
            UPDATE platform_navigation_menu SET parent_id=:parent,label_key=:labelKey,label_default=:labelDefault,
              menu_type=:type,section_key=:section,path=:path,component_key=:component,icon=:icon,permission_key=:permission,
              global_only=:globalOnly,module_id=:module,sort_order=:sort,enabled=:enabled,version=version+1,updated_at=now()
            WHERE id=:id AND version=:version AND deleted=false
            """)
                .param("id", id)
                .param("version", version)
                .param("parent", parentId)
                .param("labelKey", labelKey)
                .param("labelDefault", labelDefault)
                .param("type", menuType)
                .param("section", sectionKey)
                .param("path", path)
                .param("component", componentKey)
                .param("icon", icon)
                .param("permission", permissionKey)
                .param("globalOnly", globalOnly)
                .param("module", moduleId)
                .param("sort", sortOrder)
                .param("enabled", enabled)
                .update();
    }

    public int setEnabled(UUID id, long version, boolean enabled) {
        return jdbc.sql(
                        "UPDATE platform_navigation_menu SET enabled=:enabled,version=version+1,updated_at=now() WHERE id=:id AND version=:version AND deleted=false")
                .param("id", id)
                .param("version", version)
                .param("enabled", enabled)
                .update();
    }

    public int softDelete(UUID id, long version) {
        return jdbc.sql(
                        "UPDATE platform_navigation_menu SET deleted=true,enabled=false,version=version+1,updated_at=now() WHERE id=:id AND version=:version AND deleted=false")
                .param("id", id)
                .param("version", version)
                .update();
    }

    public int reorder(UUID id, long version, UUID parentId, int sortOrder) {
        return jdbc.sql(
                        "UPDATE platform_navigation_menu SET parent_id=:parent,sort_order=:sort,version=version+1,updated_at=now() WHERE id=:id AND version=:version AND deleted=false")
                .param("id", id)
                .param("version", version)
                .param("parent", parentId)
                .param("sort", sortOrder)
                .update();
    }
}
