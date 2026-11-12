package com.lumira.platform.files;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads persistent identity role limits without loading role administration. */
@Repository
public class FileRoleQuotaRepository {
    public record QuotaLimit(UUID roleId, boolean enabled, Long bytes, Long files) {}

    private final JdbcClient jdbc;

    public FileRoleQuotaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<QuotaLimit> assignedQuotaLimits(UUID userId) {
        return jdbc.sql(
                        "SELECT r.id,r.enabled,r.file_quota_bytes_limit,r.file_quota_files_limit FROM platform_user_role ur JOIN platform_role r ON r.id=ur.role_id WHERE ur.user_id=:id AND r.enabled ORDER BY r.id")
                .param("id", userId)
                .query(
                        (rs, n) ->
                                new QuotaLimit(
                                        rs.getObject("id", UUID.class),
                                        rs.getBoolean("enabled"),
                                        rs.getObject("file_quota_bytes_limit", Long.class),
                                        rs.getObject("file_quota_files_limit", Long.class)))
                .list();
    }

    public List<QuotaLimit> quotaLimits(Collection<UUID> roleIds) {
        if (roleIds.isEmpty()) return List.of();
        return jdbc.sql(
                        "SELECT id,enabled,file_quota_bytes_limit,file_quota_files_limit FROM platform_role WHERE id IN (:ids) ORDER BY id")
                .param("ids", roleIds)
                .query(
                        (rs, n) ->
                                new QuotaLimit(
                                        rs.getObject("id", UUID.class),
                                        rs.getBoolean("enabled"),
                                        rs.getObject("file_quota_bytes_limit", Long.class),
                                        rs.getObject("file_quota_files_limit", Long.class)))
                .list();
    }
}
