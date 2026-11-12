package com.lumira.plugins.dictionaries;

import com.lumira.platform.common.PageFilterSql;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.util.*;
import java.util.function.LongSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class DictionaryRepository {
    public record Dictionary(
            UUID id,
            String code,
            String name,
            boolean enabled,
            String structure,
            String remark,
            long version) {}

    public record DictionaryUpdateState(Dictionary dictionary, boolean hasNestedItems) {}

    public record DictionaryDeleteState(
            Dictionary dictionary, boolean hasProfileReferences, boolean hasItems) {}

    public record DictionaryState(
            Dictionary dictionary,
            boolean hasNestedItems,
            boolean hasProfileReferences,
            boolean hasItems) {}

    public record UpdateOutcome(
            boolean found,
            String oldCode,
            long oldVersion,
            boolean hasNestedItems,
            Dictionary updated) {}

    public record DeleteOutcome(
            boolean found,
            String oldCode,
            long oldVersion,
            boolean hasProfileReferences,
            boolean hasItems,
            boolean deleted) {}

    private final JdbcClient jdbc;
    private volatile Boolean auditTableAvailable;
    private static final String FILTER =
            " WHERE (:search='' OR strpos(lower(code),lower(:search))>0 OR strpos(lower(name),lower(:search))>0)";

    public DictionaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private boolean hasAuditTable() {
        Boolean cached = auditTableAvailable;
        if (cached != null) {
            return cached;
        }
        Boolean exists =
                jdbc.sql(
                                "SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON"
                                        + " n.oid = c.relnamespace WHERE n.nspname ="
                                        + " current_schema() AND c.relname = 'platform_audit')")
                        .query((rs, n) -> rs.getBoolean(1))
                        .single();
        boolean result = Boolean.TRUE.equals(exists);
        auditTableAvailable = result;
        return result;
    }

    private Dictionary row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Dictionary(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("enabled"),
                rs.getString("structure"),
                rs.getString("remark"),
                rs.getLong("version"));
    }

    private PageFilterSql.Clause filters(PageRequest page) {
        return PageFilterSql.build(
                page,
                Map.of(
                        "code",
                        "code",
                        "name",
                        "name",
                        "structure",
                        "structure",
                        "enabled",
                        "enabled"));
    }

    public long count(PageRequest p) {
        var f = filters(p);
        var params = new HashMap<String, Object>();
        params.put("search", p.search());
        params.putAll(f.parameters());
        return jdbc.sql("SELECT count(*) FROM platform_dictionary" + FILTER + f.sql())
                .params(params)
                .query((rs, n) -> rs.getLong(1))
                .single();
    }

    public List<Dictionary> list(PageRequest p) {
        var f = filters(p);
        var params = new HashMap<String, Object>();
        params.put("search", p.search());
        params.put("limit", p.pageSize());
        params.put("offset", p.offset());
        params.putAll(f.parameters());
        return jdbc.sql(
                        "SELECT id,code,name,enabled,structure,remark,version FROM platform_dictionary"
                                + FILTER
                                + f.sql()
                                + " ORDER BY code,id LIMIT :limit OFFSET :offset")
                .params(params)
                .query((rs, n) -> row(rs))
                .list();
    }

    static <T> PageResult<T> queryWindowedPage(
            JdbcClient.StatementSpec spec,
            RowMapper<T> mapper,
            PageRequest page,
            LongSupplier fallbackCount) {
        long[] total = new long[1];
        List<T> items =
                spec.query(
                                (rs, n) -> {
                                    if (n == 0) {
                                        total[0] = rs.getLong("full_count");
                                    }
                                    return mapper.mapRow(rs, n);
                                })
                        .list();
        if (!items.isEmpty()) {
            return new PageResult<>(items, total[0], page.page(), page.pageSize());
        }
        if (page.offset() == 0) {
            return new PageResult<>(List.of(), 0, page.page(), page.pageSize());
        }
        return new PageResult<>(List.of(), fallbackCount.getAsLong(), page.page(), page.pageSize());
    }

    public PageResult<Dictionary> listPage(PageRequest p) {
        var f = filters(p);
        var params = new HashMap<String, Object>();
        params.put("search", p.search());
        params.put("limit", p.pageSize());
        params.put("offset", p.offset());
        params.putAll(f.parameters());

        return queryWindowedPage(
                jdbc.sql(
                                "SELECT id,code,name,enabled,structure,remark,version,count(*) OVER() AS full_count FROM platform_dictionary"
                                        + FILTER
                                        + f.sql()
                                        + " ORDER BY code,id LIMIT :limit OFFSET :offset")
                        .params(params),
                (rs, n) -> row(rs),
                p,
                () -> count(p));
    }

    public Dictionary find(UUID id) {
        return find(id, false);
    }

    /** Lock before reading the expected version and all dependent write invariants. */
    public Dictionary findForUpdate(UUID id) {
        return find(id, true);
    }

    public DictionaryUpdateState findForUpdateWithNested(UUID id) {
        return jdbc.sql(
                        """
                        SELECT d.id, d.code, d.name, d.enabled, d.structure, d.remark, d.version,
                               EXISTS(SELECT 1 FROM platform_dictionary_item WHERE dictionary_id=d.id AND parent_id IS NOT NULL) AS has_nested_items
                        FROM platform_dictionary d
                        WHERE d.id=:id
                        FOR UPDATE OF d
                        """)
                .param("id", id)
                .query(
                        (rs, n) ->
                                new DictionaryUpdateState(
                                        row(rs), rs.getBoolean("has_nested_items")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public DictionaryDeleteState findForDelete(UUID id) {
        return jdbc.sql(
                        """
                        SELECT d.id, d.code, d.name, d.enabled, d.structure, d.remark, d.version,
                               EXISTS(SELECT 1 FROM platform_setting s WHERE s.section='profile-fields' AND s.payload @> jsonb_build_object('fields',jsonb_build_array(jsonb_build_object('dictionaryCode',d.code)))) AS has_profile_references,
                               EXISTS(SELECT 1 FROM platform_dictionary_item WHERE dictionary_id=d.id) AS has_items
                        FROM platform_dictionary d
                        WHERE d.id=:id
                        FOR UPDATE OF d
                        """)
                .param("id", id)
                .query(
                        (rs, n) ->
                                new DictionaryDeleteState(
                                        row(rs),
                                        rs.getBoolean("has_profile_references"),
                                        rs.getBoolean("has_items")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public DictionaryState findStateForUpdate(UUID id) {
        return jdbc.sql(
                        """
                        SELECT d.id, d.code, d.name, d.enabled, d.structure, d.remark, d.version,
                               EXISTS(SELECT 1 FROM platform_dictionary_item WHERE dictionary_id=d.id AND parent_id IS NOT NULL) AS has_nested_items,
                               EXISTS(SELECT 1 FROM platform_setting s WHERE s.section='profile-fields' AND s.payload @> jsonb_build_object('fields',jsonb_build_array(jsonb_build_object('dictionaryCode',d.code)))) AS has_profile_references,
                               EXISTS(SELECT 1 FROM platform_dictionary_item WHERE dictionary_id=d.id) AS has_items
                        FROM platform_dictionary d
                        WHERE d.id=:id
                        FOR UPDATE OF d
                        """)
                .param("id", id)
                .query(
                        (rs, n) ->
                                new DictionaryState(
                                        row(rs),
                                        rs.getBoolean("has_nested_items"),
                                        rs.getBoolean("has_profile_references"),
                                        rs.getBoolean("has_items")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Dictionary find(UUID id, boolean lock) {
        return jdbc.sql(
                        "SELECT id,code,name,enabled,structure,remark,version FROM platform_dictionary WHERE id=:id"
                                + (lock ? " FOR UPDATE" : ""))
                .param("id", id)
                .query((rs, n) -> row(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public Dictionary createAndAudit(
            UUID id,
            String code,
            String name,
            boolean enabled,
            String structure,
            String remark,
            UUID auditId,
            String actor) {
        if (!hasAuditTable()) {
            return null;
        }
        return jdbc.sql(
                        """
                        WITH created AS (
                            INSERT INTO platform_dictionary(id,code,name,enabled,structure,remark)
                            VALUES(:id,:code,:name,:enabled,:structure,:remark)
                            RETURNING id,code,name,enabled,structure,remark,version
                        ),
                        audited AS (
                            INSERT INTO platform_audit(id,actor,action,target_id)
                            SELECT :auditId,:actor,'dictionary.create',id FROM created
                        )
                        SELECT id,code,name,enabled,structure,remark,version FROM created
                        """)
                .param("id", id)
                .param("code", code)
                .param("name", name)
                .param("enabled", enabled)
                .param("structure", structure)
                .param("remark", remark)
                .param("auditId", auditId)
                .param("actor", actor)
                .query((rs, n) -> row(rs))
                .single();
    }

    public Dictionary create(
            UUID id, String code, String name, boolean enabled, String structure, String remark) {
        return jdbc.sql(
                        """
                        INSERT INTO platform_dictionary(id,code,name,enabled,structure,remark)
                        VALUES(:id,:code,:name,:enabled,:structure,:remark)
                        RETURNING id,code,name,enabled,structure,remark,version
                        """)
                .param("id", id)
                .param("code", code)
                .param("name", name)
                .param("enabled", enabled)
                .param("structure", structure)
                .param("remark", remark)
                .query((rs, n) -> row(rs))
                .single();
    }

    public UpdateOutcome updateCheckedAndAudit(
            UUID id,
            Long expectedVersion,
            boolean inputValid,
            String name,
            Boolean enabled,
            String structure,
            String remark,
            Collection<String> policyCodes,
            UUID auditId,
            String actor) {
        if (!hasAuditTable()) {
            return null;
        }
        if (!inputValid || expectedVersion == null || enabled == null || structure == null) {
            return jdbc.sql(
                            """
                            SELECT d.code AS old_code, d.version AS old_version,
                                   EXISTS(SELECT 1 FROM platform_dictionary_item WHERE dictionary_id=d.id AND parent_id IS NOT NULL) AS has_nested_items
                              FROM platform_dictionary d
                             WHERE d.id=:id
                               FOR UPDATE OF d
                            """)
                    .param("id", id)
                    .query(
                            (rs, n) ->
                                    new UpdateOutcome(
                                            true,
                                            rs.getString("old_code"),
                                            rs.getLong("old_version"),
                                            rs.getBoolean("has_nested_items"),
                                            null))
                    .optional()
                    .orElse(new UpdateOutcome(false, null, 0L, false, null));
        }
        return jdbc.sql(
                        """
                        WITH locked AS (
                            SELECT d.id, d.code, d.version,
                                   EXISTS(SELECT 1 FROM platform_dictionary_item WHERE dictionary_id=d.id AND parent_id IS NOT NULL) AS has_nested_items
                              FROM platform_dictionary d
                             WHERE d.id=:id
                               FOR UPDATE OF d
                        ),
                        updated AS (
                            UPDATE platform_dictionary u
                               SET name=:name, enabled=:enabled, structure=:structure, remark=:remark, version=u.version+1
                              FROM locked l
                             WHERE u.id=l.id
                               AND l.version=:expectedVersion
                               AND NOT (l.code IN (:policyCodes) AND (NOT :enabled OR :structure <> 'FLAT'))
                               AND NOT (:structure = 'FLAT' AND l.has_nested_items)
                            RETURNING u.id, u.code, u.name, u.enabled, u.structure, u.remark, u.version
                        ),
                        audited AS (
                            INSERT INTO platform_audit(id,actor,action,target_id)
                            SELECT :auditId, :actor, 'dictionary.update', id FROM updated
                        )
                        SELECT l.code AS old_code, l.version AS old_version, l.has_nested_items,
                               u.id, u.code, u.name, u.enabled, u.structure, u.remark, u.version
                          FROM locked l
                          LEFT JOIN updated u ON u.id=l.id
                        """)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .param("name", name)
                .param("enabled", enabled)
                .param("structure", structure)
                .param("remark", remark)
                .param("policyCodes", policyCodes)
                .param("auditId", auditId)
                .param("actor", actor)
                .query(
                        (rs, n) ->
                                new UpdateOutcome(
                                        true,
                                        rs.getString("old_code"),
                                        rs.getLong("old_version"),
                                        rs.getBoolean("has_nested_items"),
                                        rs.getObject("id", UUID.class) != null ? row(rs) : null))
                .optional()
                .orElse(new UpdateOutcome(false, null, 0L, false, null));
    }

    public Dictionary update(
            UUID id, String name, boolean enabled, String structure, String remark) {
        return jdbc.sql(
                        """
                        UPDATE platform_dictionary
                        SET name=:name,enabled=:enabled,structure=:structure,remark=:remark,version=version+1
                        WHERE id=:id
                        RETURNING id,code,name,enabled,structure,remark,version
                        """)
                .param("id", id)
                .param("name", name)
                .param("enabled", enabled)
                .param("structure", structure)
                .param("remark", remark)
                .query((rs, n) -> row(rs))
                .optional()
                .orElse(null);
    }

    public boolean hasItems(UUID id, boolean nestedOnly) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_dictionary_item WHERE dictionary_id=:id"
                                + (nestedOnly ? " AND parent_id IS NOT NULL" : "")
                                + ")")
                .param("id", id)
                .query((rs, n) -> rs.getBoolean(1))
                .single();
    }

    public boolean hasProfileReferences(UUID id) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_setting s CROSS JOIN platform_dictionary d WHERE d.id=:id AND s.section='profile-fields' AND s.payload @> jsonb_build_object('fields',jsonb_build_array(jsonb_build_object('dictionaryCode',d.code))))")
                .param("id", id)
                .query((rs, n) -> rs.getBoolean(1))
                .single();
    }

    public DeleteOutcome deleteCheckedAndAudit(
            UUID id,
            long expectedVersion,
            Collection<String> policyCodes,
            UUID auditId,
            String actor) {
        if (!hasAuditTable()) {
            return null;
        }
        return jdbc.sql(
                        """
                        WITH locked AS (
                            SELECT d.id, d.code, d.version,
                                   EXISTS(SELECT 1 FROM platform_setting s WHERE s.section='profile-fields' AND s.payload @> jsonb_build_object('fields',jsonb_build_array(jsonb_build_object('dictionaryCode',d.code)))) AS has_profile_references,
                                   EXISTS(SELECT 1 FROM platform_dictionary_item WHERE dictionary_id=d.id) AS has_items
                              FROM platform_dictionary d
                             WHERE d.id=:id
                               FOR UPDATE OF d
                        ),
                        deleted AS (
                            DELETE FROM platform_dictionary u
                             USING locked l
                             WHERE u.id=l.id
                               AND l.version=:expectedVersion
                               AND l.code NOT IN (:policyCodes)
                               AND NOT l.has_profile_references
                               AND NOT l.has_items
                            RETURNING u.id
                        ),
                        audited AS (
                            INSERT INTO platform_audit(id,actor,action,target_id)
                            SELECT :auditId, :actor, 'dictionary.delete', id FROM deleted
                        )
                        SELECT l.code AS old_code, l.version AS old_version, l.has_profile_references, l.has_items,
                               (d.id IS NOT NULL) AS was_deleted
                          FROM locked l
                          LEFT JOIN deleted d ON d.id=l.id
                        """)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .param("policyCodes", policyCodes)
                .param("auditId", auditId)
                .param("actor", actor)
                .query(
                        (rs, n) ->
                                new DeleteOutcome(
                                        true,
                                        rs.getString("old_code"),
                                        rs.getLong("old_version"),
                                        rs.getBoolean("has_profile_references"),
                                        rs.getBoolean("has_items"),
                                        rs.getBoolean("was_deleted")))
                .optional()
                .orElse(new DeleteOutcome(false, null, 0L, false, false, false));
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM platform_dictionary WHERE id=:id").param("id", id).update();
    }
}
