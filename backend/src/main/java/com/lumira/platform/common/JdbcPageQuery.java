package com.lumira.platform.common;

import org.springframework.jdbc.core.RowMapper;

/** Compiled SQL fragments only; caller owns transaction isolation and binds every user value. */
public final class JdbcPageQuery {
    private JdbcPageQuery() {}

    public static <T> PageResult<T> query(
            org.springframework.jdbc.core.simple.JdbcClient jdbc,
            PageRequest page,
            String from,
            String projection,
            String where,
            PageFilterSql.Clause filter,
            java.util.Map<String, Object> scope,
            String order,
            RowMapper<T> rowMapper) {
        var countParams = new java.util.HashMap<>(scope);
        countParams.put("search", page.search());
        countParams.putAll(filter.parameters());
        String source = " FROM " + from + where + filter.sql();
        long total =
                jdbc.sql("SELECT count(*)" + source).params(countParams).query(Long.class).single();
        var listParams = new java.util.HashMap<>(countParams);
        listParams.put("limit", page.pageSize());
        listParams.put("offset", page.offset());
        var items =
                jdbc.sql(
                                "SELECT "
                                        + projection
                                        + source
                                        + " ORDER BY "
                                        + order
                                        + " LIMIT :limit OFFSET :offset")
                        .params(listParams)
                        .query(rowMapper)
                        .list();
        return new PageResult<>(items, total, page.page(), page.pageSize());
    }
}
