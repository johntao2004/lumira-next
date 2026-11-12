package com.lumira.plugins.directory.users;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Durable, actor-scoped storage for import previews and idempotent task results. */
@Repository
public class UserImportStore {
    public record Preview(
            UUID id,
            String actor,
            String fileSha256,
            String mode,
            long previewVersion,
            long profileSchemaVersion,
            List<UserXlsxImportService.RowPlan> rows,
            OffsetDateTime expiresAt) {}

    public record Task(
            UUID id,
            UUID previewId,
            String actor,
            String idempotencyKey,
            long previewVersion,
            String status,
            UserXlsxImportService.TaskResult result,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt) {}

    private static final TypeReference<List<UserXlsxImportService.RowPlan>> ROWS =
            new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public UserImportStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void createPreview(
            UUID id,
            String actor,
            String fileSha256,
            String mode,
            long version,
            long schemaVersion,
            List<UserXlsxImportService.RowPlan> rows,
            OffsetDateTime expiresAt) {
        jdbc.sql(
                        "DELETE FROM platform_user_import_task WHERE completed_at < now()-interval '90 days'")
                .update();
        jdbc.sql(
                        "DELETE FROM platform_user_import_preview WHERE expires_at < now()-interval '7 days' AND NOT EXISTS (SELECT 1 FROM platform_user_import_task t WHERE t.preview_id=platform_user_import_preview.id)")
                .update();
        jdbc.sql(
                        "INSERT INTO platform_user_import_preview(id,actor,file_sha256,mode,preview_version,profile_schema_version,rows,expires_at) VALUES(:id,:actor,:sha,:mode,:version,:schema,CAST(:rows AS jsonb),:expires)")
                .param("id", id)
                .param("actor", actor)
                .param("sha", fileSha256)
                .param("mode", mode)
                .param("version", version)
                .param("schema", schemaVersion)
                .param("rows", json(rows))
                .param("expires", expiresAt)
                .update();
    }

    public Preview preview(UUID id, String actor, boolean lock) {
        return jdbc.sql(
                        "SELECT id,actor,file_sha256,mode,preview_version,profile_schema_version,rows::text,expires_at FROM platform_user_import_preview WHERE id=:id AND actor=:actor"
                                + (lock ? " FOR UPDATE" : ""))
                .param("id", id)
                .param("actor", actor)
                .query(
                        (rs, n) ->
                                new Preview(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("actor"),
                                        rs.getString("file_sha256"),
                                        rs.getString("mode"),
                                        rs.getLong("preview_version"),
                                        rs.getLong("profile_schema_version"),
                                        read(rs.getString("rows"), ROWS),
                                        rs.getObject("expires_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "importPreviewNotFound"));
    }

    public Task task(UUID id, String actor) {
        failAbandoned(actor, id, null);
        return jdbc.sql(
                        "SELECT id,preview_id,actor,idempotency_key,preview_version,status,result::text,created_at,completed_at FROM platform_user_import_task WHERE id=:id AND actor=:actor")
                .param("id", id)
                .param("actor", actor)
                .query(this::task)
                .optional()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "importTaskNotFound"));
    }

    public Task taskByIdempotency(String actor, String key) {
        failAbandoned(actor, null, key);
        return jdbc.sql(
                        "SELECT id,preview_id,actor,idempotency_key,preview_version,status,result::text,created_at,completed_at FROM platform_user_import_task WHERE actor=:actor AND idempotency_key=:key")
                .param("actor", actor)
                .param("key", key)
                .query(this::task)
                .optional()
                .orElse(null);
    }

    public void createRunningTask(UUID id, Preview preview, String key) {
        jdbc.sql(
                        "INSERT INTO platform_user_import_task(id,preview_id,actor,idempotency_key,preview_version,status,result) VALUES(:id,:preview,:actor,:key,:version,'RUNNING','{}'::jsonb)")
                .param("id", id)
                .param("preview", preview.id())
                .param("actor", preview.actor())
                .param("key", key)
                .param("version", preview.previewVersion())
                .update();
    }

    public void completeTask(UUID id, UserXlsxImportService.TaskResult result) {
        jdbc.sql(
                        "UPDATE platform_user_import_task SET status='COMPLETED',result=CAST(:result AS jsonb),completed_at=now() WHERE id=:id AND status='RUNNING'")
                .param("id", id)
                .param("result", json(result))
                .update();
    }

    public void failTask(UUID id, UserXlsxImportService.TaskResult result) {
        jdbc.sql(
                        "UPDATE platform_user_import_task SET status='FAILED',result=CAST(:result AS jsonb),completed_at=now() WHERE id=:id AND status='RUNNING'")
                .param("id", id)
                .param("result", json(result))
                .update();
    }

    private void failAbandoned(String actor, UUID id, String key) {
        String sql =
                "UPDATE platform_user_import_task SET status='FAILED',result='{"
                        + "\"status\":\"failed\",\"created\":0,\"updated\":0,\"failed\":0,\"rows\":[],\"errorCode\":\"importTaskAbandoned\"}'::jsonb,completed_at=now() WHERE actor=:actor AND status='RUNNING' AND created_at < now()-interval '15 minutes'";
        if (id != null) sql += " AND id=:id";
        if (key != null) sql += " AND idempotency_key=:key";
        var query = jdbc.sql(sql).param("actor", actor);
        if (id != null) query = query.param("id", id);
        if (key != null) query = query.param("key", key);
        query.update();
    }

    private Task task(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Task(
                rs.getObject("id", UUID.class),
                rs.getObject("preview_id", UUID.class),
                rs.getString("actor"),
                rs.getString("idempotency_key"),
                rs.getLong("preview_version"),
                rs.getString("status").toLowerCase(java.util.Locale.ROOT),
                read(rs.getString("result"), new TypeReference<>() {}),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Import task data cannot be encoded", failure);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (Exception failure) {
            throw new IllegalStateException("Stored import data is invalid", failure);
        }
    }
}
