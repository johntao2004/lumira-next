package com.lumira.plugins.directory.users;

import com.lumira.platform.common.PageFilterSql;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcClient jdbc;
    private final com.lumira.platform.common.UserDirectoryPort accounts;

    private static String visible(RequestAuthorizationContext context) {
        return context != null && context.previewActive()
                ? "platform_role_preview_visible_users(:actor,:scopePermission,:targetRoleId,:targetRoleVersion)"
                : "platform_visible_users(:actor,:scopePermission)";
    }

    private static String filter(
            RequestAuthorizationContext context, String scopePermission, String pagePermission) {
        String visibleScope = visible(context);
        String visiblePage = visible(context).replace(":scopePermission", ":pagePermission");
        return " WHERE deleted_at IS NULL AND id IN (SELECT user_id FROM "
                + visibleScope
                + " INTERSECT SELECT user_id FROM "
                + visiblePage
                + ") AND (:search = '' OR strpos(lower(username), lower(:search)) > 0 OR strpos(lower(display_name), lower(:search)) > 0)";
    }

    private static void contextParams(
            java.util.Map<String, Object> params, RequestAuthorizationContext context) {
        if (context != null && context.previewActive()) {
            params.put("targetRoleId", context.targetRoleId());
            params.put("targetRoleVersion", context.preview().roleVersion());
        }
    }

    public UserRepository(JdbcClient jdbc, com.lumira.platform.common.UserDirectoryPort accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    public String preferredLocale(String actor) {
        return jdbc.sql(
                        "SELECT preferred_locale FROM platform_user WHERE username=:actor AND enabled AND deleted_at IS NULL")
                .param("actor", actor)
                .query((rs, row) -> rs.getString("preferred_locale"))
                .optional()
                .orElse("zh-CN");
    }

    private PageFilterSql.Clause filters(PageRequest page) {
        return PageFilterSql.build(
                page,
                java.util.Map.of(
                        "username",
                        "username",
                        "displayName",
                        "display_name",
                        "enabled",
                        "enabled"));
    }

    public long count(PageRequest request, String actor) {
        return count(request, actor, null);
    }

    public long count(PageRequest request, String actor, RequestAuthorizationContext context) {
        return count(request, actor, context, "platform:users:access", "platform:users:access");
    }

    public long count(
            PageRequest request,
            String actor,
            RequestAuthorizationContext context,
            String scopePermission) {
        return count(request, actor, context, scopePermission, "platform:users:access");
    }

    public long count(
            PageRequest request,
            String actor,
            RequestAuthorizationContext context,
            String scopePermission,
            String pagePermission) {
        var filter = filters(request);
        var params = new java.util.HashMap<String, Object>();
        params.put("actor", actor);
        params.put("scopePermission", scopePermission);
        params.put("pagePermission", pagePermission);
        params.put("search", request.search());
        params.putAll(filter.parameters());
        contextParams(params, context);
        return jdbc.sql(
                        "SELECT count(*) FROM platform_user"
                                + filter(context, scopePermission, pagePermission)
                                + filter.sql())
                .params(params)
                .query(Long.class)
                .single();
    }

    public List<UserSummary> findPage(PageRequest request, String actor) {
        return findPage(request, actor, null);
    }

    public List<UserSummary> findPage(
            PageRequest request, String actor, RequestAuthorizationContext context) {
        return findPage(request, actor, context, "platform:users:access", "platform:users:access");
    }

    public List<UserSummary> findPage(
            PageRequest request,
            String actor,
            RequestAuthorizationContext context,
            String scopePermission) {
        return findPage(request, actor, context, scopePermission, "platform:users:access");
    }

    public List<UserSummary> findPage(
            PageRequest request,
            String actor,
            RequestAuthorizationContext context,
            String scopePermission,
            String pagePermission) {
        var filter = filters(request);
        var params = new java.util.HashMap<String, Object>();
        params.put("actor", actor);
        params.put("scopePermission", scopePermission);
        params.put("pagePermission", pagePermission);
        params.put("search", request.search());
        params.put("limit", request.pageSize());
        params.put("offset", request.offset());
        params.putAll(filter.parameters());
        contextParams(params, context);
        return jdbc.sql(
                        "SELECT id, username, display_name, enabled, version,file_quota_bytes_override,file_quota_files_override FROM platform_user"
                                + filter(context, scopePermission, pagePermission)
                                + filter.sql()
                                + " ORDER BY username, id LIMIT :limit OFFSET :offset")
                .params(params)
                .query(UserRepository::mapSummary)
                .list();
    }

    public List<UserSummary> findExport(PageRequest request, String actor, int limit) {
        return findExport(request, actor, limit, null);
    }

    public List<UserSummary> findExport(
            PageRequest request, String actor, int limit, RequestAuthorizationContext context) {
        return findExport(
                request, actor, limit, context, "platform:users:access", "platform:users:access");
    }

    public List<UserSummary> findExport(
            PageRequest request,
            String actor,
            int limit,
            RequestAuthorizationContext context,
            String scopePermission) {
        return findExport(request, actor, limit, context, scopePermission, "platform:users:access");
    }

    public List<UserSummary> findExport(
            PageRequest request,
            String actor,
            int limit,
            RequestAuthorizationContext context,
            String scopePermission,
            String pagePermission) {
        var filter = filters(request);
        var params = new java.util.HashMap<String, Object>();
        params.put("actor", actor);
        params.put("scopePermission", scopePermission);
        params.put("pagePermission", pagePermission);
        params.put("search", request.search());
        params.put("limit", limit);
        params.putAll(filter.parameters());
        contextParams(params, context);
        return jdbc.sql(
                        "SELECT id, username, display_name, enabled, version,file_quota_bytes_override,file_quota_files_override FROM platform_user"
                                + filter(context, scopePermission, pagePermission)
                                + filter.sql()
                                + " ORDER BY username, id LIMIT :limit")
                .params(params)
                .query(UserRepository::mapSummary)
                .list();
    }

    public List<UserSummary> findSelected(
            java.util.Set<UUID> ids,
            String actor,
            RequestAuthorizationContext context,
            String scopePermission,
            String pagePermission) {
        if (ids == null || ids.isEmpty()) return List.of();
        if (ids.size() > 4_999)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "batchLimitExceeded");
        var params = new java.util.HashMap<String, Object>();
        params.put("actor", actor);
        params.put("scopePermission", scopePermission);
        params.put("pagePermission", pagePermission);
        params.put("ids", ids);
        contextParams(params, context);
        return jdbc.sql(
                        "SELECT id,username,display_name,enabled,version,file_quota_bytes_override,file_quota_files_override FROM platform_user WHERE deleted_at IS NULL AND id IN (:ids) AND id IN (SELECT user_id FROM "
                                + visible(context)
                                + ") AND id IN (SELECT user_id FROM "
                                + visible(context).replace(":scopePermission", ":pagePermission")
                                + ") ORDER BY username,id")
                .params(params)
                .query(UserRepository::mapSummary)
                .list();
    }

    public UserSummary findByUsernameVisible(
            String username,
            String actor,
            RequestAuthorizationContext context,
            String scopePermission,
            String pagePermission) {
        var params = new java.util.HashMap<String, Object>();
        params.put("username", username);
        params.put("actor", actor);
        params.put("scopePermission", scopePermission);
        params.put("pagePermission", pagePermission);
        contextParams(params, context);
        return jdbc.sql(
                        "SELECT id,username,display_name,enabled,version,file_quota_bytes_override,file_quota_files_override FROM platform_user WHERE username=:username AND deleted_at IS NULL AND id IN (SELECT user_id FROM "
                                + visible(context)
                                + ") AND id IN (SELECT user_id FROM "
                                + visible(context).replace(":scopePermission", ":pagePermission")
                                + ")")
                .params(params)
                .query(UserRepository::mapSummary)
                .optional()
                .orElse(null);
    }

    public List<UserSummary> findExport(String search, String actor, int limit) {
        return findExport(new PageRequest(1, 1, search), actor, limit);
    }

    public UserSummary find(UUID id) {
        return jdbc.sql(
                        "SELECT id,username,display_name,enabled,version,file_quota_bytes_override,file_quota_files_override FROM platform_user WHERE id=:id AND deleted_at IS NULL FOR UPDATE")
                .param("id", id)
                .query(UserRepository::mapSummary)
                .optional()
                .orElseThrow(
                        () ->
                                new org.springframework.web.server.ResponseStatusException(
                                        org.springframework.http.HttpStatus.NOT_FOUND));
    }

    public UserSummary findSnapshot(UUID id) {
        return jdbc.sql(
                        "SELECT id,username,display_name,enabled,version,file_quota_bytes_override,file_quota_files_override FROM platform_user WHERE id=:id AND deleted_at IS NULL")
                .param("id", id)
                .query(UserRepository::mapSummary)
                .optional()
                .orElseThrow(
                        () ->
                                new org.springframework.web.server.ResponseStatusException(
                                        org.springframework.http.HttpStatus.NOT_FOUND));
    }

    static UserSummary mapSummary(ResultSet rs, int row) throws SQLException {
        return new UserSummary(
                rs.getObject("id", UUID.class),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                rs.getObject("file_quota_bytes_override", Long.class),
                rs.getObject("file_quota_files_override", Long.class));
    }

    public long version(UUID id) {
        return accounts.version(id);
    }

    public long versionSnapshot(UUID id) {
        return accounts.versionSnapshot(id);
    }

    public boolean isLastAdministrator(UUID id) {
        return jdbc.sql("SELECT platform_is_last_recovery_administrator(:id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public void create(UUID id, String username, String displayName, String hash) {
        jdbc.sql(
                        "INSERT INTO platform_user(id,username,display_name,password_hash) VALUES(:id,:username,:displayName,:hash)")
                .param("id", id)
                .param("username", username)
                .param("displayName", displayName)
                .param("hash", hash)
                .update();
    }

    /**
     * Registration-only insert; a concurrent username collision must not abort the caller
     * transaction.
     */
    public void update(UUID id, String displayName, boolean enabled) {
        jdbc.sql(
                        "UPDATE platform_user SET display_name=:name,enabled=:enabled,version=version+1 WHERE id=:id AND deleted_at IS NULL")
                .param("id", id)
                .param("name", displayName)
                .param("enabled", enabled)
                .update();
    }

    public int updateBasicProfile(
            UUID id, String realName, String birthMonth, String region, long expectedVersion) {
        return jdbc.sql(
                        "UPDATE platform_user SET real_name=CASE WHEN :writeReal THEN :realName ELSE real_name END,birth_month=CASE WHEN :writeBirth THEN :birthMonth ELSE birth_month END,region=CASE WHEN :writeRegion THEN :region ELSE region END,version=version+1 WHERE id=:id AND version=:version AND deleted_at IS NULL")
                .param("id", id)
                .param("version", expectedVersion)
                .param("writeReal", realName != null)
                .param("realName", realName == null ? "" : realName)
                .param("writeBirth", birthMonth != null)
                .param("birthMonth", birthMonth == null ? "" : birthMonth)
                .param("writeRegion", region != null)
                .param("region", region == null ? "" : region)
                .update();
    }

    public int softDelete(UUID id, long version) {
        return jdbc.sql(
                        "UPDATE platform_user SET enabled=false,deleted_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=:id AND version=:version AND deleted_at IS NULL")
                .param("id", id)
                .param("version", version)
                .update();
    }

    public void revokeSessions(String username) {
        jdbc.sql("DELETE FROM spring_session WHERE principal_name=:name")
                .param("name", username)
                .update();
    }
}
