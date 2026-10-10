package com.lumira.platform.dictionaries;

import com.lumira.platform.common.PageFilterSql;
import com.lumira.platform.common.PageRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
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

    private final JdbcClient jdbc;
    private static final String FILTER =
            " WHERE (:search='' OR strpos(lower(code),lower(:search))>0 OR strpos(lower(name),lower(:search))>0)";

    public DictionaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
                .query(Long.class)
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
                        "SELECT * FROM platform_dictionary"
                                + FILTER
                                + f.sql()
                                + " ORDER BY code,id LIMIT :limit OFFSET :offset")
                .params(params)
                .query((rs, n) -> row(rs))
                .list();
    }

    public Dictionary find(UUID id) {
        return jdbc.sql("SELECT * FROM platform_dictionary WHERE id=:id")
                .param("id", id)
                .query((rs, n) -> row(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public void create(
            UUID id, String code, String name, boolean enabled, String structure, String remark) {
        jdbc.sql(
                        "INSERT INTO platform_dictionary(id,code,name,enabled,structure,remark) VALUES(:id,:code,:name,:enabled,:structure,:remark)")
                .param("id", id)
                .param("code", code)
                .param("name", name)
                .param("enabled", enabled)
                .param("structure", structure)
                .param("remark", remark)
                .update();
    }

    public void update(UUID id, String name, boolean enabled, String structure, String remark) {
        jdbc.sql(
                        "UPDATE platform_dictionary SET name=:name,enabled=:enabled,structure=:structure,remark=:remark,version=version+1 WHERE id=:id")
                .param("id", id)
                .param("name", name)
                .param("enabled", enabled)
                .param("structure", structure)
                .param("remark", remark)
                .update();
    }

    public boolean hasItems(UUID id, boolean nestedOnly) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_dictionary_item WHERE dictionary_id=:id"
                                + (nestedOnly ? " AND parent_id IS NOT NULL" : "")
                                + ")")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public boolean hasProfileReferences(UUID id) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_setting s CROSS JOIN platform_dictionary d WHERE d.id=:id AND s.section='profile-fields' AND s.payload @> jsonb_build_object('fields',jsonb_build_array(jsonb_build_object('dictionaryCode',d.code))))")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM platform_dictionary WHERE id=:id").param("id", id).update();
    }
}
