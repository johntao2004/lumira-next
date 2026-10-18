package com.lumira.platform.directory.templates;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class TemplateRepository {
    public record Summary(
            UUID id,
            String name,
            String category,
            List<String> tags,
            String status,
            long version,
            Integer draftVersion,
            Integer publishedVersion,
            OffsetDateTime updatedAt) {}

    public record Version(
            UUID templateId,
            int version,
            String state,
            String filename,
            String sha256,
            byte[] source,
            Map<String, Object> inspection,
            Map<String, String> bindings,
            String createdBy,
            OffsetDateTime createdAt) {}

    private static final TypeReference<List<String>> TAG_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public TemplateRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Summary> list(
            int page, int pageSize, String search, String category, String tag, String status) {
        var params = new java.util.HashMap<String, Object>();
        params.put("search", search == null ? "" : search.strip());
        params.put("category", category == null ? "" : category.strip());
        params.put("tag", tag == null ? "" : tag.strip());
        params.put(
                "status", status == null ? "" : status.strip().toUpperCase(java.util.Locale.ROOT));
        params.put("limit", pageSize);
        params.put("offset", (long) (page - 1) * pageSize);
        String where =
                " WHERE (:search='' OR strpos(lower(name),lower(:search))>0)"
                        + " AND (:category='' OR category=:category)"
                        + " AND (:tag='' OR jsonb_exists(tags,:tag))"
                        + " AND (:status='' OR status=:status)";
        return jdbc.sql(
                        "SELECT * FROM platform_print_template"
                                + where
                                + " ORDER BY updated_at DESC,id LIMIT :limit OFFSET :offset")
                .params(params)
                .query(this::summary)
                .list();
    }

    public long count(String search, String category, String tag, String status) {
        var params = new java.util.HashMap<String, Object>();
        params.put("search", search == null ? "" : search.strip());
        params.put("category", category == null ? "" : category.strip());
        params.put("tag", tag == null ? "" : tag.strip());
        params.put(
                "status", status == null ? "" : status.strip().toUpperCase(java.util.Locale.ROOT));
        return jdbc.sql(
                        "SELECT count(*) FROM platform_print_template WHERE (:search='' OR strpos(lower(name),lower(:search))>0) AND (:category='' OR category=:category) AND (:tag='' OR jsonb_exists(tags,:tag)) AND (:status='' OR status=:status)")
                .params(params)
                .query(Long.class)
                .single();
    }

    public Summary find(UUID id, boolean lock) {
        return jdbc.sql(
                        "SELECT * FROM platform_print_template WHERE id=:id"
                                + (lock ? " FOR UPDATE" : ""))
                .param("id", id)
                .query(this::summary)
                .optional()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "templateNotFound"));
    }

    public Version version(UUID id, int version) {
        return jdbc.sql(
                        "SELECT template_id,version,state,source_filename,source_sha256,source_bytes,inspection::text,bindings::text,created_by,created_at FROM platform_print_template_version WHERE template_id=:id AND version=:version")
                .param("id", id)
                .param("version", version)
                .query(this::version)
                .optional()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "templateVersionNotFound"));
    }

    public List<Version> versions(UUID id) {
        return jdbc.sql(
                        "SELECT template_id,version,state,source_filename,source_sha256,NULL::bytea AS source_bytes,inspection::text,bindings::text,created_by,created_at FROM platform_print_template_version WHERE template_id=:id ORDER BY version DESC LIMIT 100")
                .param("id", id)
                .query(this::version)
                .list();
    }

    public int maxVersion(UUID id) {
        return jdbc.sql(
                        "SELECT COALESCE(max(version),0) FROM platform_print_template_version WHERE template_id=:id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    public int create(
            UUID id,
            String name,
            String category,
            List<String> tags,
            String actor,
            String filename,
            byte[] source,
            String sha256,
            Map<String, Object> inspection,
            Map<String, String> bindings) {
        jdbc.sql(
                        "INSERT INTO platform_print_template(id,name,category,tags,status,draft_version,version,created_by) VALUES(:id,:name,:category,CAST(:tags AS jsonb),'DRAFT',1,1,:actor)")
                .param("id", id)
                .param("name", name)
                .param("category", category)
                .param("tags", json(tags))
                .param("actor", actor)
                .update();
        insertVersion(id, 1, actor, filename, source, sha256, inspection, bindings);
        return 1;
    }

    public int appendDraft(
            UUID id,
            long expectedVersion,
            String actor,
            String filename,
            byte[] source,
            String sha256,
            Map<String, Object> inspection) {
        var current = find(id, true);
        requireVersion(current, expectedVersion);
        jdbc.sql(
                        "UPDATE platform_print_template_version SET state='SUPERSEDED' WHERE template_id=:id AND state='DRAFT'")
                .param("id", id)
                .update();
        int version = maxVersion(id) + 1;
        insertVersion(id, version, actor, filename, source, sha256, inspection, Map.of());
        jdbc.sql(
                        "UPDATE platform_print_template SET status=CASE WHEN status='INACTIVE' THEN 'INACTIVE' WHEN published_version IS NULL THEN 'DRAFT' ELSE 'PUBLISHED' END,draft_version=:draft,version=version+1,updated_at=now() WHERE id=:id")
                .param("id", id)
                .param("draft", version)
                .update();
        return version;
    }

    public int clonePublishedDraft(UUID id, long expectedVersion, String actor) {
        var current = find(id, true);
        requireVersion(current, expectedVersion);
        if (current.draftVersion() != null) return current.draftVersion();
        if (current.publishedVersion() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "templateDraftMissing");
        var published = version(id, current.publishedVersion());
        int draft = maxVersion(id) + 1;
        insertVersion(
                id,
                draft,
                actor,
                published.filename(),
                published.source(),
                published.sha256(),
                published.inspection(),
                published.bindings());
        jdbc.sql(
                        "UPDATE platform_print_template SET draft_version=:draft,version=version+1,updated_at=now() WHERE id=:id")
                .param("id", id)
                .param("draft", draft)
                .update();
        return draft;
    }

    public void updateMetadata(
            UUID id, long expectedVersion, String name, String category, List<String> tags) {
        var current = find(id, true);
        requireVersion(current, expectedVersion);
        jdbc.sql(
                        "UPDATE platform_print_template SET name=:name,category=:category,tags=CAST(:tags AS jsonb),version=version+1,updated_at=now() WHERE id=:id")
                .param("id", id)
                .param("name", name)
                .param("category", category)
                .param("tags", json(tags))
                .update();
    }

    public void updateBindings(
            UUID id, long expectedTemplateVersion, int draftVersion, Map<String, String> bindings) {
        var current = find(id, true);
        requireVersion(current, expectedTemplateVersion);
        if (current.draftVersion() == null || current.draftVersion() != draftVersion)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        jdbc.sql(
                        "UPDATE platform_print_template_version SET bindings=CAST(:bindings AS jsonb) WHERE template_id=:id AND version=:version AND state='DRAFT'")
                .param("bindings", json(bindings))
                .param("id", id)
                .param("version", draftVersion)
                .update();
        jdbc.sql(
                        "UPDATE platform_print_template SET version=version+1,updated_at=now() WHERE id=:id")
                .param("id", id)
                .update();
    }

    public void publish(UUID id, long expectedVersion) {
        var current = find(id, true);
        requireVersion(current, expectedVersion);
        if (current.draftVersion() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "templateDraftMissing");
        jdbc.sql(
                        "UPDATE platform_print_template_version SET state='PUBLISHED',published_at=now() WHERE template_id=:id AND version=:version AND state='DRAFT'")
                .param("id", id)
                .param("version", current.draftVersion())
                .update();
        jdbc.sql(
                        "UPDATE platform_print_template SET status='PUBLISHED',published_version=:version,draft_version=NULL,version=version+1,updated_at=now() WHERE id=:id")
                .param("id", id)
                .param("version", current.draftVersion())
                .update();
    }

    public void deactivate(UUID id, long expectedVersion) {
        var current = find(id, true);
        requireVersion(current, expectedVersion);
        jdbc.sql(
                        "UPDATE platform_print_template SET status='INACTIVE',version=version+1,updated_at=now() WHERE id=:id")
                .param("id", id)
                .update();
    }

    private void insertVersion(
            UUID id,
            int version,
            String actor,
            String filename,
            byte[] source,
            String sha256,
            Map<String, Object> inspection,
            Map<String, String> bindings) {
        jdbc.sql(
                        "INSERT INTO platform_print_template_version(template_id,version,state,source_filename,source_sha256,source_bytes,inspection,bindings,created_by) VALUES(:id,:version,'DRAFT',:filename,:sha,:source,CAST(:inspection AS jsonb),CAST(:bindings AS jsonb),:actor)")
                .param("id", id)
                .param("version", version)
                .param("filename", filename)
                .param("sha", sha256)
                .param("source", source)
                .param("inspection", json(inspection))
                .param("bindings", json(bindings))
                .param("actor", actor)
                .update();
    }

    private Summary summary(ResultSet rs, int row) throws SQLException {
        return new Summary(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("category"),
                read(rs.getString("tags"), TAG_LIST),
                rs.getString("status").toLowerCase(java.util.Locale.ROOT),
                rs.getLong("version"),
                rs.getObject("draft_version", Integer.class),
                rs.getObject("published_version", Integer.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private Version version(ResultSet rs, int row) throws SQLException {
        return new Version(
                rs.getObject("template_id", UUID.class),
                rs.getInt("version"),
                rs.getString("state").toLowerCase(java.util.Locale.ROOT),
                rs.getString("source_filename"),
                rs.getString("source_sha256"),
                rs.getBytes("source_bytes"),
                read(rs.getString("inspection"), OBJECT_MAP),
                read(rs.getString("bindings"), STRING_MAP),
                rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception failure) {
            throw new IllegalStateException("Stored template metadata is invalid", failure);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Template metadata cannot be encoded", failure);
        }
    }

    private static void requireVersion(Summary current, long expectedVersion) {
        if (current.version() != expectedVersion)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
    }
}
