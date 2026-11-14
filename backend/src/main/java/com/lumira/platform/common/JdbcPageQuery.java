package com.lumira.platform.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Compiled SQL fragments only; caller owns transaction isolation and binds every user value. */
public final class JdbcPageQuery {
    private static final String TOTAL_ALIAS = "__lumira_total_count";

    private JdbcPageQuery() {}

    public static <T> PageResult<T> query(
            JdbcClient jdbc,
            PageRequest page,
            String from,
            String projection,
            String where,
            PageFilterSql.Clause filter,
            Map<String, Object> scope,
            String order,
            RowMapper<T> rowMapper) {
        var baseParams = new HashMap<String, Object>(scope.size() + filter.parameters().size() + 1);
        baseParams.putAll(scope);
        baseParams.put("search", page.search());
        baseParams.putAll(filter.parameters());
        String source = " FROM " + from + where + filter.sql();
        return queryWindowed(jdbc, page, projection, source, order, baseParams, rowMapper);
    }

    /**
     * Executes pagination and total-row counting in a single PostgreSQL round trip via {@code
     * count(*) OVER()}, issuing a fallback count query only when a non-zero offset returns an empty
     * page.
     */
    public static <T> PageResult<T> queryWindowed(
            JdbcClient jdbc,
            PageRequest page,
            String projection,
            String source,
            String order,
            Map<String, ?> baseParams,
            RowMapper<T> rowMapper) {
        var listParams = new HashMap<String, Object>(baseParams.size() + 2);
        listParams.putAll(baseParams);
        listParams.put("limit", page.pageSize());
        listParams.put("offset", page.offset());
        long[] totalHolder = new long[1];
        List<T> items =
                jdbc.sql(
                                "SELECT "
                                        + projection
                                        + ", count(*) OVER() AS "
                                        + TOTAL_ALIAS
                                        + source
                                        + " ORDER BY "
                                        + order
                                        + " LIMIT :limit OFFSET :offset")
                        .params(listParams)
                        .query(
                                (ResultSetExtractor<List<T>>)
                                        rs -> {
                                            var rows = new ArrayList<T>();
                                            int rowNum = 0;
                                            while (rs.next()) {
                                                if (rowNum == 0) {
                                                    totalHolder[0] = rs.getLong(TOTAL_ALIAS);
                                                }
                                                rows.add(rowMapper.mapRow(rs, rowNum++));
                                            }
                                            return rows;
                                        });
        long total = totalHolder[0];
        if (items.isEmpty() && page.offset() > 0) {
            total =
                    jdbc.sql("SELECT count(*)" + source)
                            .params(baseParams)
                            .query(Long.class)
                            .single();
        }
        return new PageResult<>(items, total, page.page(), page.pageSize());
    }
}
