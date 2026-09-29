package com.lumira.platform.users;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageFilterSql;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository implements com.lumira.platform.common.UserDirectoryPort {
    private final JdbcClient jdbc;
    private static final String FILTER = " WHERE deleted_at IS NULL AND id IN (SELECT user_id FROM platform_visible_users(:actor,'platform:users:read')) AND (:search = '' OR strpos(lower(username), lower(:search)) > 0 OR strpos(lower(display_name), lower(:search)) > 0)";

    public UserRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private PageFilterSql.Clause filters(PageRequest page) {
        return PageFilterSql.build(page, java.util.Map.of("username", "username", "displayName", "display_name", "enabled", "enabled"));
    }

    public long count(PageRequest request,String actor) {
        var filter=filters(request);var params=new java.util.HashMap<String,Object>();params.put("actor",actor);params.put("search",request.search());params.putAll(filter.parameters());
        return jdbc.sql("SELECT count(*) FROM platform_user" + FILTER+filter.sql()).params(params).query(Long.class).single();
    }

    public List<UserSummary> findPage(PageRequest request,String actor) {
        var filter=filters(request);var params=new java.util.HashMap<String,Object>();params.put("actor",actor);params.put("search",request.search());params.put("limit",request.pageSize());params.put("offset",request.offset());params.putAll(filter.parameters());
        return jdbc.sql("SELECT id, username, display_name, enabled, version,file_quota_bytes_override,file_quota_files_override FROM platform_user" + FILTER+filter.sql()
                + " ORDER BY username, id LIMIT :limit OFFSET :offset")
            .params(params)
            .query((rs, row) -> new UserSummary(rs.getObject("id", UUID.class), rs.getString("username"),
                rs.getString("display_name"), rs.getBoolean("enabled"), rs.getLong("version"),
                rs.getObject("file_quota_bytes_override",Long.class),rs.getObject("file_quota_files_override",Long.class))).list();
    }

    public List<UserSummary> findExport(PageRequest request, String actor, int limit) {
        var filter=filters(request);
        var params=new java.util.HashMap<String,Object>();params.put("actor",actor);params.put("search",request.search());params.put("limit",limit);params.putAll(filter.parameters());
        return jdbc.sql("SELECT id, username, display_name, enabled, version,file_quota_bytes_override,file_quota_files_override FROM platform_user" + FILTER + filter.sql()
                + " ORDER BY username, id LIMIT :limit")
            .params(params).query((rs, row) -> new UserSummary(
                rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name"),
                rs.getBoolean("enabled"), rs.getLong("version"),rs.getObject("file_quota_bytes_override",Long.class),
                rs.getObject("file_quota_files_override",Long.class))).list();
    }

    public List<UserSummary> findExport(String search, String actor, int limit) {
        return findExport(new PageRequest(1, 1, search), actor, limit);
    }

    public UserSummary find(UUID id) {
        return jdbc.sql("SELECT id,username,display_name,enabled,version,file_quota_bytes_override,file_quota_files_override FROM platform_user WHERE id=:id AND deleted_at IS NULL FOR UPDATE")
            .param("id",id).query((rs,n)->new UserSummary(rs.getObject("id",UUID.class),rs.getString("username"),rs.getString("display_name"),rs.getBoolean("enabled"),rs.getLong("version"),rs.getObject("file_quota_bytes_override",Long.class),rs.getObject("file_quota_files_override",Long.class)))
            .optional().orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    }
    public UserSummary findSnapshot(UUID id) {
        return jdbc.sql("SELECT id,username,display_name,enabled,version,file_quota_bytes_override,file_quota_files_override FROM platform_user WHERE id=:id AND deleted_at IS NULL")
            .param("id",id).query((rs,n)->new UserSummary(rs.getObject("id",UUID.class),rs.getString("username"),rs.getString("display_name"),rs.getBoolean("enabled"),rs.getLong("version"),rs.getObject("file_quota_bytes_override",Long.class),rs.getObject("file_quota_files_override",Long.class)))
            .optional().orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    }
    @Override
    public long version(UUID id) {
        return jdbc.sql("SELECT version FROM platform_user WHERE id=:id AND deleted_at IS NULL FOR UPDATE")
            .param("id", id).query(Long.class).optional()
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    }
    @Override
    public long versionSnapshot(UUID id) {
        return jdbc.sql("SELECT version FROM platform_user WHERE id=:id AND deleted_at IS NULL")
            .param("id", id).query(Long.class).optional()
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    }
    public boolean isLastAdministrator(UUID id) {
        return jdbc.sql("SELECT platform_is_last_recovery_administrator(:id)")
            .param("id",id).query(Boolean.class).single();
    }
    public void create(UUID id,String username,String displayName,String hash) {
        jdbc.sql("INSERT INTO platform_user(id,username,display_name,password_hash) VALUES(:id,:username,:displayName,:hash)")
            .param("id",id).param("username",username).param("displayName",displayName).param("hash",hash).update();
    }
    public void update(UUID id,String displayName,boolean enabled) {
        jdbc.sql("UPDATE platform_user SET display_name=:name,enabled=:enabled,version=version+1 WHERE id=:id AND deleted_at IS NULL")
            .param("id",id).param("name",displayName).param("enabled",enabled).update();
    }
    public int softDelete(UUID id,long version) {
        return jdbc.sql("UPDATE platform_user SET enabled=false,deleted_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=:id AND version=:version AND deleted_at IS NULL")
            .param("id",id).param("version",version).update();
    }
    public void revokeSessions(String username) { jdbc.sql("DELETE FROM spring_session WHERE principal_name=:name").param("name",username).update(); }
}
