package com.lumira.platform.dictionaries;

import com.lumira.platform.common.PageFilterSql;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.ParentFilter;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class DictionaryItemRepository {
    public record Item(
            UUID id,
            UUID dictionaryId,
            UUID parentId,
            String value,
            String label,
            int sortOrder,
            boolean enabled,
            String remark,
            long version) {}

    private final JdbcClient jdbc;
    private static final String FILTER =
            " WHERE dictionary_id=:dictionary AND (:search='' OR strpos(lower(value),lower(:search))>0 OR strpos(lower(label),lower(:search))>0)";

    public DictionaryItemRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private Item row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Item(
                rs.getObject("id", UUID.class),
                rs.getObject("dictionary_id", UUID.class),
                rs.getObject("parent_id", UUID.class),
                rs.getString("value"),
                rs.getString("label"),
                rs.getInt("sort_order"),
                rs.getBoolean("enabled"),
                rs.getString("remark"),
                rs.getLong("version"));
    }

    public Item find(UUID dictionary, UUID id) {
        return jdbc.sql(
                        "SELECT * FROM platform_dictionary_item WHERE dictionary_id=:dictionary AND id=:id")
                .param("dictionary", dictionary)
                .param("id", id)
                .query((rs, n) -> row(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private PageFilterSql.Clause filters(PageRequest page) {
        return PageFilterSql.build(
                page,
                Map.of(
                        "value",
                        "value",
                        "label",
                        "label",
                        "sortOrder",
                        "sort_order",
                        "enabled",
                        "enabled"));
    }

    public long count(UUID dictionary, PageRequest p, ParentFilter parent) {
        var f = filters(p);
        var params = new HashMap<String, Object>();
        params.put("dictionary", dictionary);
        params.put("search", p.search());
        params.putAll(f.parameters());
        return parent.bind(
                        jdbc.sql(
                                        "SELECT count(*) FROM platform_dictionary_item"
                                                + FILTER
                                                + parent.predicate()
                                                + f.sql())
                                .params(params))
                .query(Long.class)
                .single();
    }

    public List<Item> list(UUID dictionary, PageRequest p, ParentFilter parent) {
        var f = filters(p);
        var params = new HashMap<String, Object>();
        params.put("dictionary", dictionary);
        params.put("search", p.search());
        params.put("limit", p.pageSize());
        params.put("offset", p.offset());
        params.putAll(f.parameters());
        return parent.bind(
                        jdbc.sql(
                                        "SELECT * FROM platform_dictionary_item"
                                                + FILTER
                                                + parent.predicate()
                                                + f.sql()
                                                + " ORDER BY sort_order,value,id LIMIT :limit OFFSET :offset")
                                .params(params))
                .query((rs, n) -> row(rs))
                .list();
    }

    public List<Item> ancestors(UUID dictionary, UUID id) {
        return jdbc.sql(
                        "WITH RECURSIVE ancestors AS (SELECT i.*,0 AS depth FROM platform_dictionary_item i WHERE dictionary_id=:dictionary AND id=:id UNION ALL SELECT i.*,a.depth+1 FROM platform_dictionary_item i JOIN ancestors a ON i.id=a.parent_id WHERE i.dictionary_id=:dictionary) SELECT * FROM ancestors ORDER BY depth DESC")
                .param("dictionary", dictionary)
                .param("id", id)
                .query((rs, n) -> row(rs))
                .list();
    }

    public List<Item> all(UUID dictionary) {
        return jdbc.sql(
                        "SELECT * FROM platform_dictionary_item WHERE dictionary_id=:dictionary ORDER BY sort_order,value,id")
                .param("dictionary", dictionary)
                .query((rs, n) -> row(rs))
                .list();
    }

    public boolean cycles(UUID dictionary, UUID id, UUID parent) {
        return jdbc.sql(
                        "WITH RECURSIVE ancestors AS (SELECT id,parent_id FROM platform_dictionary_item WHERE dictionary_id=:dictionary AND id=:parent UNION SELECT i.id,i.parent_id FROM platform_dictionary_item i JOIN ancestors a ON i.id=a.parent_id WHERE i.dictionary_id=:dictionary) SELECT EXISTS(SELECT 1 FROM ancestors WHERE id=:id)")
                .param("dictionary", dictionary)
                .param("parent", parent)
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public void save(UUID dictionary, UUID id, DictionaryItemService.Input in, boolean create) {
        String sql =
                create
                        ? "INSERT INTO platform_dictionary_item(id,dictionary_id,parent_id,value,label,sort_order,enabled,remark) VALUES(:id,:dictionary,:parent,:value,:label,:sort,:enabled,:remark)"
                        : "UPDATE platform_dictionary_item SET parent_id=:parent,value=:value,label=:label,sort_order=:sort,enabled=:enabled,remark=:remark,version=version+1 WHERE dictionary_id=:dictionary AND id=:id";
        jdbc.sql(sql)
                .param("dictionary", dictionary)
                .param("id", id)
                .param("parent", in.parentId())
                .param("value", in.value().strip())
                .param("label", in.label().strip())
                .param("sort", in.sortOrder())
                .param("enabled", in.enabled())
                .param("remark", in.remark() == null ? "" : in.remark())
                .update();
    }

    public boolean hasChildren(UUID dictionary, UUID id) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_dictionary_item WHERE dictionary_id=:dictionary AND parent_id=:id)")
                .param("dictionary", dictionary)
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public boolean hasProfileSelections(UUID id) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_profile_dictionary_selection WHERE item_id=:id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public void delete(UUID dictionary, UUID id) {
        jdbc.sql("DELETE FROM platform_dictionary_item WHERE dictionary_id=:dictionary AND id=:id")
                .param("dictionary", dictionary)
                .param("id", id)
                .update();
    }
}
