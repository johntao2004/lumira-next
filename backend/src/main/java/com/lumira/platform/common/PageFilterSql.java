package com.lumira.platform.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds parameterized field filters for paginated resource queries. Column expressions are always repository-owned allowlists. */
public final class PageFilterSql {
    public record Clause(String sql, Map<String, Object> parameters) {}

    private PageFilterSql() {}

    public static Clause build(PageRequest page, Map<String, String> columns) {
        var sql = new StringBuilder();
        var parameters = new LinkedHashMap<String, Object>();
        int index = 0;
        for (var column : columns.entrySet()) {
            String field = column.getKey();
            String expression = column.getValue();
            String mode = page.filterModes().getOrDefault(field, "contains");
            if ("range".equals(mode)) {
                String from = page.filters().get(field + ".from");
                String to = page.filters().get(field + ".to");
                if (from != null && !from.isBlank()) {
                    String parameter = "list_filter_" + index++;
                    sql.append(" AND ").append(expression).append(" >= CAST(:").append(parameter).append(" AS timestamptz)");
                    parameters.put(parameter, from);
                }
                if (to != null && !to.isBlank()) {
                    String parameter = "list_filter_" + index++;
                    sql.append(" AND ").append(expression).append(" <= CAST(:").append(parameter).append(" AS timestamptz)");
                    parameters.put(parameter, to);
                }
                continue;
            }

            String value = page.filters().get(field);
            if (value == null || value.isBlank()) continue;
            String parameter = "list_filter_" + index++;
            if ("exact".equals(mode)) {
                sql.append(" AND CAST(").append(expression).append(" AS text) = :").append(parameter);
                parameters.put(parameter, value);
            } else {
                sql.append(" AND strpos(lower(coalesce(CAST(").append(expression).append(" AS text), '')), lower(:").append(parameter).append(")) > 0");
                parameters.put(parameter, value);
            }
        }
        return new Clause(sql.toString(), Map.copyOf(parameters));
    }
}
