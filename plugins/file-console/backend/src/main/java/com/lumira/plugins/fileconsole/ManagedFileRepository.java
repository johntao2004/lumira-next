package com.lumira.plugins.fileconsole;

import com.lumira.platform.common.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Global file-center queries and state transitions, independent of the storage kernel. */
@Repository
public class ManagedFileRepository {
    private final JdbcClient jdbc;

    public ManagedFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(
            UUID id,
            UUID ownerId,
            String ownerUsername,
            String ownerDisplayName,
            String storageBackend,
            String originalName,
            String state,
            Long sizeBytes,
            String sha256,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant trashedAt,
            boolean protectedByReference) {
        public Entry(
                UUID id,
                UUID ownerId,
                String ownerUsername,
                String ownerDisplayName,
                String storageBackend,
                String originalName,
                String state,
                Long sizeBytes,
                String sha256,
                long version,
                Instant createdAt,
                Instant updatedAt,
                Instant trashedAt) {
            this(
                    id,
                    ownerId,
                    ownerUsername,
                    ownerDisplayName,
                    storageBackend,
                    originalName,
                    state,
                    sizeBytes,
                    sha256,
                    version,
                    createdAt,
                    updatedAt,
                    trashedAt,
                    false);
        }
    }

    private static final String MANAGED_PROJECTION =
            "f.*,u.username AS owner_username,u.display_name AS owner_display_name,(EXISTS (SELECT"
                    + " 1 FROM platform_account_media am WHERE am.avatar_file_id=f.id) OR EXISTS"
                    + " (SELECT 1 FROM platform_feedback_file ff WHERE ff.file_id=f.id) OR EXISTS"
                    + " (SELECT 1 FROM platform_branding_file bf WHERE bf.file_id=f.id)) AS"
                    + " protected_by_reference";
    private static final RowMapper<Entry> MANAGED_MAPPER =
            (rs, n) ->
                    new Entry(
                            rs.getObject("id", UUID.class),
                            rs.getObject("owner_id", UUID.class),
                            rs.getString("owner_username"),
                            rs.getString("owner_display_name"),
                            rs.getString("storage_backend"),
                            rs.getString("original_name"),
                            rs.getString("state"),
                            rs.getObject("size_bytes", Long.class),
                            rs.getString("sha256"),
                            rs.getLong("version"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("updated_at").toInstant(),
                            rs.getTimestamp("trashed_at") == null
                                    ? null
                                    : rs.getTimestamp("trashed_at").toInstant(),
                            rs.getBoolean("protected_by_reference"));

    public PageResult<Entry> listManaged(PageRequest page) {
        String where =
                " WHERE f.state NOT IN ('TRASHED','DELETING') AND (:search='' OR"
                        + " strpos(lower(f.original_name),lower(:search))>0 OR"
                        + " strpos(lower(coalesce(u.username,'')),lower(:search))>0 OR"
                        + " strpos(lower(coalesce(u.display_name,'')),lower(:search))>0)";
        var filter =
                PageFilterSql.build(
                        page,
                        java.util.Map.of(
                                "originalName",
                                "f.original_name",
                                "ownerUsername",
                                "CASE WHEN u.username IS NULL THEN '—' ELSE"
                                        + " coalesce(u.display_name,u.username) || ' (' || u.username"
                                        + " || ')' END",
                                "sizeBytes",
                                "f.size_bytes",
                                "state",
                                "f.state",
                                "createdAt",
                                "f.created_at"),
                        java.util.Set.of("createdAt"));
        return JdbcPageQuery.query(
                jdbc,
                page,
                "platform_file f LEFT JOIN platform_user u ON u.id=f.owner_id",
                MANAGED_PROJECTION,
                where,
                filter,
                java.util.Map.of(),
                "f.created_at DESC,f.id DESC",
                MANAGED_MAPPER);
    }

    public PageResult<Entry> listManagedTrash(PageRequest page) {
        String where =
                " WHERE f.state='TRASHED' AND (:search='' OR"
                        + " strpos(lower(f.original_name),lower(:search))>0 OR"
                        + " strpos(lower(coalesce(u.username,'')),lower(:search))>0 OR"
                        + " strpos(lower(coalesce(u.display_name,'')),lower(:search))>0)";
        var filter =
                PageFilterSql.build(
                        page,
                        java.util.Map.of(
                                "originalName",
                                "f.original_name",
                                "ownerUsername",
                                "CASE WHEN u.username IS NULL THEN '—' ELSE"
                                        + " coalesce(u.display_name,u.username) || ' (' || u.username"
                                        + " || ')' END",
                                "sizeBytes",
                                "f.size_bytes",
                                "trashedAt",
                                "f.trashed_at"),
                        java.util.Set.of("trashedAt"));
        return JdbcPageQuery.query(
                jdbc,
                page,
                "platform_file f LEFT JOIN platform_user u ON u.id=f.owner_id",
                MANAGED_PROJECTION,
                where,
                filter,
                java.util.Map.of(),
                "f.trashed_at DESC,f.id DESC",
                MANAGED_MAPPER);
    }

    public Optional<Entry> findManaged(UUID id) {
        return jdbc.sql(
                        "SELECT "
                                + MANAGED_PROJECTION
                                + " FROM platform_file f LEFT JOIN platform_user u ON"
                                + " u.id=f.owner_id WHERE f.id=:id")
                .param("id", id)
                .query(MANAGED_MAPPER)
                .optional();
    }

    public boolean requestManagedTrash(UUID id, long version) {
        if (jdbc.sql("SELECT id FROM platform_file WHERE id=:id FOR UPDATE")
                .param("id", id)
                .query(UUID.class)
                .optional()
                .isEmpty()) return false;
        return jdbc.sql(
                                "UPDATE platform_file SET"
                                        + " state='TRASHED',trashed_at=clock_timestamp(),version=version+1,updated_at=now()"
                                        + " WHERE id=:id AND version=:version AND state='READY' AND NOT"
                                        + " EXISTS(SELECT 1 FROM platform_account_media WHERE"
                                        + " avatar_file_id=:id) AND NOT EXISTS(SELECT 1 FROM"
                                        + " platform_feedback_file WHERE file_id=:id) AND NOT"
                                        + " EXISTS(SELECT 1 FROM platform_branding_file WHERE"
                                        + " file_id=:id)")
                        .param("id", id)
                        .param("version", version)
                        .update()
                == 1;
    }

    public boolean restoreManaged(UUID id, long version) {
        if (jdbc.sql("SELECT id FROM platform_file WHERE id=:id FOR UPDATE")
                .param("id", id)
                .query(UUID.class)
                .optional()
                .isEmpty()) return false;
        return jdbc.sql(
                                "UPDATE platform_file SET"
                                        + " state='READY',trashed_at=NULL,version=version+1,updated_at=now()"
                                        + " WHERE id=:id AND version=:version AND state='TRASHED'")
                        .param("id", id)
                        .param("version", version)
                        .update()
                == 1;
    }

    public boolean purgeManaged(UUID id, long version) {
        if (jdbc.sql("SELECT id FROM platform_file WHERE id=:id FOR UPDATE")
                .param("id", id)
                .query(UUID.class)
                .optional()
                .isEmpty()) return false;
        return jdbc.sql(
                                "UPDATE platform_file SET"
                                        + " state='DELETING',trashed_at=NULL,version=version+1,updated_at=now()"
                                        + " WHERE id=:id AND version=:version AND state='TRASHED' AND"
                                        + " NOT EXISTS(SELECT 1 FROM platform_branding_file WHERE"
                                        + " file_id=:id)")
                        .param("id", id)
                        .param("version", version)
                        .update()
                == 1;
    }
}
