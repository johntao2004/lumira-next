package com.lumira.platform.localization;

import com.lumira.platform.common.PageFilterSql;
import com.lumira.platform.common.PageRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class LocalizationRepository {
    private static final long CATALOG_WRITE_LOCK = 0x4c554d495241L;

    public record Entry(
            UUID id,
            String messageKey,
            String namespace,
            String defaultMessage,
            Map<String, String> translations,
            boolean enabled,
            long version,
            String createdBy,
            String updatedBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        public Entry {
            translations = Map.copyOf(translations == null ? Map.of() : translations);
        }
    }

    public record BundleRow(
            String messageKey, String defaultMessage, Map<String, String> translations) {
        public BundleRow {
            translations = Map.copyOf(translations == null ? Map.of() : translations);
        }
    }

    private static final String SELECT =
            "SELECT id,message_key,namespace,default_message,translations::text AS translations,enabled,version,created_by,updated_by,created_at,updated_at FROM platform_localization_entry";
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public LocalizationRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private Map<String, String> translations(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            var type =
                    mapper.getTypeFactory()
                            .constructMapType(LinkedHashMap.class, String.class, String.class);
            @SuppressWarnings("unchecked")
            Map<String, String> parsed = (Map<String, String>) mapper.readValue(value, type);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException invalidJson) {
            throw new IllegalStateException(
                    "Invalid localization translations stored in database", invalidJson);
        }
    }

    private Entry row(ResultSet rs) throws SQLException {
        return new Entry(
                rs.getObject("id", UUID.class),
                rs.getString("message_key"),
                rs.getString("namespace"),
                rs.getString("default_message"),
                translations(rs.getString("translations")),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                rs.getString("created_by"),
                rs.getString("updated_by"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static String json(ObjectMapper mapper, Map<String, String> values) {
        return mapper.writeValueAsString(values == null ? Map.of() : values);
    }

    private static String filter(Boolean enabled) {
        return " WHERE deleted=false AND (:search='' OR strpos(lower(message_key),lower(:search))>0 OR strpos(lower(namespace),lower(:search))>0 OR strpos(lower(default_message),lower(:search))>0)"
                + (enabled == null ? "" : " AND enabled=:enabled");
    }

    public long count(PageRequest page, Boolean enabled) {
        var clause =
                PageFilterSql.build(
                        page,
                        Map.of(
                                "messageKey",
                                "message_key",
                                "namespace",
                                "namespace",
                                "defaultMessage",
                                "default_message",
                                "translations",
                                "translations::text",
                                "enabled",
                                "enabled"));
        var query =
                jdbc.sql(
                                "SELECT count(*) FROM platform_localization_entry"
                                        + filter(enabled)
                                        + clause.sql())
                        .param("search", page.search())
                        .params(clause.parameters());
        if (enabled != null) query = query.param("enabled", enabled);
        return query.query(Long.class).single();
    }

    public List<Entry> list(PageRequest page, Boolean enabled) {
        var clause =
                PageFilterSql.build(
                        page,
                        Map.of(
                                "messageKey",
                                "message_key",
                                "namespace",
                                "namespace",
                                "defaultMessage",
                                "default_message",
                                "translations",
                                "translations::text",
                                "enabled",
                                "enabled"));
        var query =
                jdbc.sql(
                                SELECT
                                        + filter(enabled)
                                        + clause.sql()
                                        + " ORDER BY updated_at DESC,id LIMIT :limit OFFSET :offset")
                        .param("search", page.search())
                        .param("limit", page.pageSize())
                        .param("offset", page.offset())
                        .params(clause.parameters());
        if (enabled != null) query = query.param("enabled", enabled);
        return query.query((rs, n) -> row(rs)).list();
    }

    public Entry find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id=:id AND deleted=false")
                .param("id", id)
                .query((rs, n) -> row(rs))
                .optional()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "localizationNotFound"));
    }

    /** All non-deleted overlays used to compose the complete static-plus-database catalogue. */
    public List<Entry> allActive() {
        return jdbc.sql(SELECT + " WHERE deleted=false ORDER BY message_key,id")
                .query((rs, n) -> row(rs))
                .list();
    }

    public Optional<Entry> findByKey(String messageKey) {
        return jdbc.sql(SELECT + " WHERE message_key=:key AND deleted=false")
                .param("key", messageKey)
                .query((rs, n) -> row(rs))
                .optional();
    }

    public boolean exists(String messageKey, UUID exceptId) {
        var query =
                jdbc.sql(
                                "SELECT EXISTS(SELECT 1 FROM platform_localization_entry WHERE message_key=:key AND deleted=false"
                                        + (exceptId == null ? ")" : " AND id<>:exceptId)"))
                        .param("key", messageKey);
        if (exceptId != null) query = query.param("exceptId", exceptId);
        return query.query(Boolean.class).single();
    }

    public void create(
            UUID id,
            String messageKey,
            String namespace,
            String defaultMessage,
            Map<String, String> translations,
            boolean enabled,
            String actor) {
        jdbc.sql(
                        "INSERT INTO platform_localization_entry(id,message_key,namespace,default_message,translations,enabled,created_by,updated_by) VALUES(:id,:key,:namespace,:defaultMessage,CAST(:translations AS jsonb),:enabled,:actor,:actor)")
                .param("id", id)
                .param("key", messageKey)
                .param("namespace", namespace)
                .param("defaultMessage", defaultMessage)
                .param("translations", json(mapper, translations))
                .param("enabled", enabled)
                .param("actor", actor)
                .update();
    }

    /**
     * Insert a first locale override for a static key. The unique index fences concurrent first
     * writes.
     */
    public int insertOverride(
            UUID id,
            String messageKey,
            String namespace,
            String defaultMessage,
            String locale,
            String value,
            String actor) {
        return jdbc.sql(
                        """
            INSERT INTO platform_localization_entry(id,message_key,namespace,default_message,translations,enabled,created_by,updated_by)
            VALUES(:id,:key,:namespace,:defaultMessage,jsonb_build_object(:locale,:value),true,:actor,:actor)
            ON CONFLICT DO NOTHING
            """)
                .param("id", id)
                .param("key", messageKey)
                .param("namespace", namespace)
                .param("defaultMessage", defaultMessage)
                .param("locale", locale)
                .param("value", value)
                .param("actor", actor)
                .update();
    }

    /**
     * Atomic locale patch that retains every other locale, even when concurrent writers touch other
     * keys.
     */
    public int updateTranslation(UUID id, long version, String locale, String value, String actor) {
        return jdbc.sql(
                        """
            UPDATE platform_localization_entry
            SET translations=translations || jsonb_build_object(:locale,:value),updated_by=:actor,updated_at=now(),version=version+1
            WHERE id=:id AND version=:version AND deleted=false
            """)
                .param("id", id)
                .param("version", version)
                .param("locale", locale)
                .param("value", value)
                .param("actor", actor)
                .update();
    }

    /** Atomically removes only the selected locale override, preserving all other translations. */
    public int removeTranslation(UUID id, long version, String locale, String actor) {
        return jdbc.sql(
                        """
            UPDATE platform_localization_entry
            SET translations=translations - :locale,updated_by=:actor,updated_at=now(),version=version+1
            WHERE id=:id AND version=:version AND deleted=false AND jsonb_exists(translations,:locale)
            """)
                .param("id", id)
                .param("version", version)
                .param("locale", locale)
                .param("actor", actor)
                .update();
    }

    public int update(
            UUID id,
            long version,
            String messageKey,
            String namespace,
            String defaultMessage,
            Map<String, String> translations,
            boolean enabled,
            String actor) {
        return jdbc.sql(
                        "UPDATE platform_localization_entry SET message_key=:key,namespace=:namespace,default_message=:defaultMessage,translations=CAST(:translations AS jsonb),enabled=:enabled,updated_by=:actor,updated_at=now(),version=version+1 WHERE id=:id AND version=:version AND deleted=false")
                .param("id", id)
                .param("version", version)
                .param("key", messageKey)
                .param("namespace", namespace)
                .param("defaultMessage", defaultMessage)
                .param("translations", json(mapper, translations))
                .param("enabled", enabled)
                .param("actor", actor)
                .update();
    }

    public int setEnabled(UUID id, long version, boolean enabled, String actor) {
        return jdbc.sql(
                        "UPDATE platform_localization_entry SET enabled=:enabled,updated_by=:actor,updated_at=now(),version=version+1 WHERE id=:id AND version=:version AND deleted=false")
                .param("id", id)
                .param("version", version)
                .param("enabled", enabled)
                .param("actor", actor)
                .update();
    }

    public int softDelete(UUID id, long version, String actor) {
        return jdbc.sql(
                        "UPDATE platform_localization_entry SET deleted=true,enabled=false,updated_by=:actor,updated_at=now(),version=version+1 WHERE id=:id AND version=:version AND deleted=false")
                .param("id", id)
                .param("version", version)
                .param("actor", actor)
                .update();
    }

    /** The partial unique index remains the final fence for concurrent imports. */
    public int insertIfAbsent(
            UUID id,
            String messageKey,
            String namespace,
            String defaultMessage,
            Map<String, String> translations,
            String actor) {
        return jdbc.sql(
                        "INSERT INTO platform_localization_entry(id,message_key,namespace,default_message,translations,enabled,created_by,updated_by) VALUES(:id,:key,:namespace,:defaultMessage,CAST(:translations AS jsonb),true,:actor,:actor) ON CONFLICT DO NOTHING")
                .param("id", id)
                .param("key", messageKey)
                .param("namespace", namespace)
                .param("defaultMessage", defaultMessage)
                .param("translations", json(mapper, translations))
                .param("actor", actor)
                .update();
    }

    public List<BundleRow> bundle() {
        return jdbc.sql(
                        "SELECT message_key,default_message,translations::text AS translations FROM platform_localization_entry WHERE deleted=false AND enabled=true ORDER BY message_key,id")
                .query(
                        (rs, n) ->
                                new BundleRow(
                                        rs.getString("message_key"),
                                        rs.getString("default_message"),
                                        translations(rs.getString("translations"))))
                .list();
    }

    public long releaseVersion(String locale) {
        return jdbc.sql(
                        "SELECT release_version FROM platform_localization_release WHERE locale_code=:locale")
                .param("locale", locale)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    /** Serializes every catalog mutation with source-snapshot validation and translation saves. */
    public void lockCatalogForWrite() {
        jdbc.sql("SELECT pg_advisory_xact_lock(:lockKey)")
                .param("lockKey", CATALOG_WRITE_LOCK)
                .query((rs, row) -> Boolean.TRUE)
                .single();
    }

    public long publish(String locale, String actor) {
        return jdbc.sql(
                        "INSERT INTO platform_localization_release(locale_code,release_version,published_by) VALUES(:locale,1,:actor) ON CONFLICT(locale_code) DO UPDATE SET release_version=platform_localization_release.release_version+1,published_by=EXCLUDED.published_by,published_at=now() RETURNING release_version")
                .param("locale", locale)
                .param("actor", actor)
                .query(Long.class)
                .single();
    }
}
