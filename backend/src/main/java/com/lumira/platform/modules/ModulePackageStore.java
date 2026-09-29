package com.lumira.platform.modules;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists immutable, digest-addressed module package metadata.
 *
 * <p>The store deliberately never reads or executes an artifact. Runtime code remains
 * deployment-owned; this table is the durable hand-off between package verification and
 * a later application rollout.</p>
 */
@Repository
public class ModulePackageStore {
    public record Entry(UUID id, String moduleId, String packageVersion, String schemaVersion,
                        String apiVersion, String artifactDigest, String manifestDigest,
                        String artifactRef, String manifest, String status, long revision,
                        String installedBy, String installReason, OffsetDateTime createdAt,
                        OffsetDateTime updatedAt, OffsetDateTime activatedAt,
                        OffsetDateTime supersededAt) {
    }

    public record Stage(UUID id, String moduleId, String packageVersion, String schemaVersion,
                        String apiVersion, String artifactDigest, String manifestDigest,
                        String artifactRef, String manifest, String installedBy,
                        String installReason) {
    }

    public record Activation(Entry entry, boolean changed) {
    }

    public record StageResult(Entry entry, boolean created) {
    }

    public static class Rejected extends IllegalStateException {
        public Rejected(String code) {
            super(code);
        }
    }

    private static final String SELECT = "SELECT id,module_id,package_version,schema_version,api_version,"
            + "artifact_digest,manifest_digest,artifact_ref,manifest::text AS manifest,status,revision,"
            + "installed_by,install_reason,created_at,updated_at,activated_at,superseded_at "
            + "FROM platform_module_package";
    private final JdbcClient jdbc;

    public ModulePackageStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private Entry row(ResultSet rs) throws SQLException {
        return new Entry(
                rs.getObject("id", UUID.class),
                rs.getString("module_id"),
                rs.getString("package_version"),
                rs.getString("schema_version"),
                rs.getString("api_version"),
                rs.getString("artifact_digest"),
                rs.getString("manifest_digest"),
                rs.getString("artifact_ref"),
                rs.getString("manifest"),
                rs.getString("status"),
                rs.getLong("revision"),
                rs.getString("installed_by"),
                rs.getString("install_reason"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("activated_at", OffsetDateTime.class),
                rs.getObject("superseded_at", OffsetDateTime.class));
    }

    public Optional<Entry> find(UUID id) {
        if (id == null) return Optional.empty();
        return jdbc.sql(SELECT + " WHERE id=:id")
                .param("id", id)
                .query((rs, n) -> row(rs))
                .optional();
    }

    public List<Entry> list(String moduleId) {
        var query = jdbc.sql(SELECT + " WHERE (:module='' OR module_id=:module) "
                        + "ORDER BY module_id,created_at DESC,id DESC")
                .param("module", moduleId == null ? "" : moduleId);
        return query.query((rs, n) -> row(rs)).list();
    }

    @Transactional
    public StageResult stage(Stage input) {
        UUID id = input.id() == null ? UUID.randomUUID() : input.id();
        int inserted = jdbc.sql("INSERT INTO platform_module_package(id,module_id,package_version,schema_version,api_version,"
                        + "artifact_digest,manifest_digest,artifact_ref,manifest,status,revision,installed_by,install_reason) "
                        + "VALUES(:id,:module,:version,:schema,:api,:digest,:manifestDigest,:ref,CAST(:manifest AS jsonb),"
                        + "'STAGED',1,:actor,:reason) ON CONFLICT(module_id,package_version,artifact_digest) DO NOTHING")
                .param("id", id)
                .param("module", input.moduleId())
                .param("version", input.packageVersion())
                .param("schema", input.schemaVersion())
                .param("api", input.apiVersion())
                .param("digest", input.artifactDigest())
                .param("manifestDigest", input.manifestDigest())
                .param("ref", input.artifactRef())
                .param("manifest", input.manifest())
                .param("actor", input.installedBy())
                .param("reason", input.installReason())
                .update();
        if (inserted == 1) {
            var result = find(id);
            if (result.isPresent()) return new StageResult(result.get(), true);
        }
        var existing = findIdentity(input.moduleId(), input.packageVersion(), input.artifactDigest())
                .orElseThrow(() -> new IllegalStateException("staged module package disappeared"));
        if ("REJECTED".equals(existing.status())) throw new Rejected("packageAlreadyRejected");
        return new StageResult(existing, false);
    }

    public Optional<Entry> findIdentity(String moduleId, String packageVersion, String artifactDigest) {
        return jdbc.sql(SELECT + " WHERE module_id=:module AND package_version=:version AND artifact_digest=:digest")
                .param("module", moduleId).param("version", packageVersion).param("digest", artifactDigest)
                .query((rs, n) -> row(rs)).optional();
    }

    /** Serialize package activation with module enable/disable changes. */
    @Transactional
    public Activation activate(UUID id, long expectedRevision) {
        lockGraph();
        Entry current = lock(id);
        if (current.revision() != expectedRevision) throw new Rejected("stalePackage");
        if ("ACTIVE".equals(current.status())) return new Activation(current, false);
        if (!"STAGED".equals(current.status())) throw new Rejected("packageStateConflict");
        jdbc.sql("UPDATE platform_module_package SET status='SUPERSEDED',superseded_at=now(),updated_at=now(),revision=revision+1 "
                        + "WHERE module_id=:module AND status='ACTIVE' AND id<>:id")
                .param("module", current.moduleId()).param("id", current.id()).update();
        int updated = jdbc.sql("UPDATE platform_module_package SET status='ACTIVE',activated_at=now(),updated_at=now(),revision=revision+1 "
                        + "WHERE id=:id AND status='STAGED' AND revision=:revision")
                .param("id", current.id()).param("revision", expectedRevision).update();
        if (updated != 1) throw new Rejected("stalePackage");
        return new Activation(find(id).orElseThrow(), true);
    }

    @Transactional
    public Entry reject(UUID id, long expectedRevision) {
        lockGraph();
        Entry current = lock(id);
        if (current.revision() != expectedRevision) throw new Rejected("stalePackage");
        if (!"STAGED".equals(current.status())) throw new Rejected("packageStateConflict");
        int updated = jdbc.sql("UPDATE platform_module_package SET status='REJECTED',updated_at=now(),revision=revision+1 "
                        + "WHERE id=:id AND status='STAGED' AND revision=:revision")
                .param("id", id).param("revision", expectedRevision).update();
        if (updated != 1) throw new Rejected("stalePackage");
        return find(id).orElseThrow();
    }

    private Entry lock(UUID id) {
        return jdbc.sql(SELECT + " WHERE id=:id FOR UPDATE")
                .param("id", id)
                .query((rs, n) -> row(rs))
                .optional()
                .orElseThrow(() -> new Rejected("packageNotFound"));
    }

    private void lockGraph() {
        jdbc.sql("SELECT pg_advisory_xact_lock(481027)").query(rs -> {
            rs.next();
            return 0;
        });
    }
}
