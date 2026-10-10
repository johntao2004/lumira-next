package com.lumira.platform.settings;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal persistence shared by typed setting sections; raw JSON is never an API write contract.
 */
@Repository
public class SettingStore {
    static final String SYSTEM_SAVE_REASON = "System-generated record: platform settings saved.";
    static final String SYSTEM_RESTORE_REASON_PREFIX =
            "System-generated record: platform settings restored from revision ";

    public record Snapshot(UUID id, long version, String payload) {}

    public record Revision(
            long version,
            String payload,
            String changedBy,
            String changeReason,
            OffsetDateTime createdAt,
            Long sourceVersion) {}

    public record RevisionSummary(
            long version,
            String changedBy,
            String changeReason,
            OffsetDateTime createdAt,
            Long sourceVersion) {}

    private final JdbcClient jdbc;
    private final AdministrationPort guard;
    private final AuditPort audit;

    public SettingStore(JdbcClient jdbc, AdministrationPort guard, AuditPort audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    public Optional<Snapshot> find(String section) {
        return jdbc.sql(
                        "SELECT id,version,payload::text AS payload FROM platform_setting WHERE section=:section")
                .param("section", section)
                .query(Snapshot.class)
                .optional();
    }

    public PageResult<RevisionSummary> history(String section, PageRequest page) {
        var setting = find(section);
        if (setting.isEmpty())
            return new PageResult<>(java.util.List.of(), 0, page.page(), page.pageSize());
        UUID id = setting.orElseThrow().id();
        var rows =
                jdbc.sql(
                                """
                        SELECT version,changed_by AS changedBy,change_reason AS changeReason,
                               created_at AS createdAt,source_version AS sourceVersion
                        FROM platform_setting_revision
                        WHERE setting_id=:id
                        ORDER BY version DESC
                        LIMIT :limit OFFSET :offset
                        """)
                        .param("id", id)
                        .param("limit", page.pageSize())
                        .param("offset", page.offset())
                        .query(RevisionSummary.class)
                        .list();
        long total =
                jdbc.sql("SELECT count(*) FROM platform_setting_revision WHERE setting_id=:id")
                        .param("id", id)
                        .query(Long.class)
                        .single();
        return new PageResult<>(rows, total, page.page(), page.pageSize());
    }

    public Optional<Revision> revision(String section, long version) {
        return jdbc.sql(
                        """
                        SELECT r.version,r.payload::text AS payload,r.changed_by AS changedBy,
                               r.change_reason AS changeReason,r.created_at AS createdAt,
                               r.source_version AS sourceVersion
                        FROM platform_setting_revision r
                        JOIN platform_setting s ON s.id=r.setting_id
                        WHERE s.section=:section AND r.version=:version
                        """)
                .param("section", section)
                .param("version", version)
                .query(Revision.class)
                .optional();
    }

    @Transactional
    public Snapshot save(
            String section,
            String payload,
            String actor,
            long expectedVersion,
            String changeReason) {
        return save(section, payload, actor, expectedVersion, changeReason, null);
    }

    @Transactional
    public Snapshot save(
            String section,
            String payload,
            String actor,
            long expectedVersion,
            String changeReason,
            Long sourceVersion) {
        guard.require(actor, "platform:settings:write");
        guard.requireGlobal(actor, "platform:settings:write");
        String reason = changeReason == null ? "" : changeReason.strip();
        if (reason.length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidChangeReason");
        }
        if (reason.isEmpty()) {
            reason =
                    sourceVersion == null
                            ? SYSTEM_SAVE_REASON
                            : SYSTEM_RESTORE_REASON_PREFIX + sourceVersion + ".";
        }
        if (expectedVersion < 0
                || expectedVersion == Long.MAX_VALUE
                || sourceVersion != null && sourceVersion < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidVersion");
        }
        if (sourceVersion != null
                && !jdbc.sql(
                                """
                SELECT EXISTS(
                    SELECT 1 FROM platform_setting_revision r
                    JOIN platform_setting s ON s.id=r.setting_id
                    WHERE s.section=:section AND r.version=:sourceVersion
                )
                """)
                        .param("section", section)
                        .param("sourceVersion", sourceVersion)
                        .query(Boolean.class)
                        .single()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "settingRevisionNotFound");
        }

        if (expectedVersion == 0) {
            var inserted =
                    jdbc.sql(
                                    """
                        INSERT INTO platform_setting(id,section,version,payload,updated_by)
                        VALUES(:id,:section,1,CAST(:payload AS jsonb),:actor)
                        ON CONFLICT(section) DO NOTHING
                        RETURNING id,version,payload::text AS payload
                        """)
                            .param("id", UUID.randomUUID())
                            .param("section", section)
                            .param("payload", payload)
                            .param("actor", actor)
                            .query(Snapshot.class)
                            .optional()
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.CONFLICT, "staleRecord"));
            writeRevision(inserted, payload, actor, reason, sourceVersion);
            audit.append(actor, "setting.update", inserted.id());
            return inserted;
        }

        jdbc.sql(
                        """
                INSERT INTO platform_setting_revision(
                    setting_id,version,payload,changed_by,change_reason,created_at,source_version
                )
                SELECT id,version,payload,updated_by,
                       'Configuration before revision tracking',updated_at,NULL
                FROM platform_setting
                WHERE section=:section AND version=:expectedVersion
                ON CONFLICT DO NOTHING
                """)
                .param("section", section)
                .param("expectedVersion", expectedVersion)
                .update();

        Snapshot saved =
                jdbc.sql(
                                """
                        UPDATE platform_setting
                        SET version=version+1,
                            payload=CAST(:payload AS jsonb),
                            updated_by=:actor,
                            updated_at=now()
                        WHERE section=:section AND version=:expectedVersion
                        RETURNING id,version,payload::text AS payload
                        """)
                        .param("payload", payload)
                        .param("actor", actor)
                        .param("section", section)
                        .param("expectedVersion", expectedVersion)
                        .query(Snapshot.class)
                        .optional()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.CONFLICT, "staleRecord"));

        writeRevision(saved, payload, actor, reason, sourceVersion);
        audit.append(actor, "setting.update", saved.id());
        return saved;
    }

    private void writeRevision(
            Snapshot saved, String payload, String actor, String reason, Long sourceVersion) {
        jdbc.sql(
                        """
                INSERT INTO platform_setting_revision(
                    setting_id,version,payload,changed_by,change_reason,source_version
                ) VALUES(:settingId,:version,CAST(:payload AS jsonb),:actor,:reason,:sourceVersion)
                """)
                .param("settingId", saved.id())
                .param("version", saved.version())
                .param("payload", payload)
                .param("actor", actor)
                .param("reason", reason)
                .param("sourceVersion", sourceVersion)
                .update();
    }
}
