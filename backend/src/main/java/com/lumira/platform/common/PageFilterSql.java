package com.lumira.platform.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds parameterized field filters for paginated resource queries. Column expressions are always
 * repository-owned allowlists.
 */
public final class PageFilterSql {
    public record Clause(String sql, Map<String, Object> parameters) {}

    private PageFilterSql() {}

    public static Clause build(PageRequest page, Map<String, String> columns) {
        return build(page, columns, Set.of());
    }

    public static Clause build(
            PageRequest page, Map<String, String> columns, Set<String> timeColumns) {
        var sql = new StringBuilder();
        var parameters = new LinkedHashMap<String, Object>();
        int index = 0;
        for (var column : columns.entrySet()) {
            String field = column.getKey();
            String expression = column.getValue();
            String mode = page.filterModes().getOrDefault(field, "contains");
            if ("range".equals(mode)) {
                if (!timeColumns.contains(field)) throw invalidRange();
                String from = page.filters().get(field + ".from");
                String to = page.filters().get(field + ".to");
                var start = boundary(from);
                var end = boundary(to);
                if (start != null && end != null && start.after(end)) throw invalidRange();
                if (from != null && !from.isBlank()) {
                    String parameter = "list_filter_" + index++;
                    sql.append(" AND ")
                            .append(expression)
                            .append(" >= CAST(:")
                            .append(parameter)
                            .append(" AS timestamptz)");
                    parameters.put(parameter, start.value());
                }
                if (to != null && !to.isBlank()) {
                    String parameter = "list_filter_" + index++;
                    sql.append(" AND ")
                            .append(expression)
                            .append(" <= CAST(:")
                            .append(parameter)
                            .append(" AS timestamptz)");
                    parameters.put(parameter, end.value());
                }
                continue;
            }

            String value = page.filters().get(field);
            if (value == null || value.isBlank()) continue;
            String parameter = "list_filter_" + index++;
            if ("exact".equals(mode)) {
                sql.append(" AND CAST(")
                        .append(expression)
                        .append(" AS text) = :")
                        .append(parameter);
                parameters.put(parameter, value);
            } else {
                sql.append(" AND strpos(lower(coalesce(CAST(")
                        .append(expression)
                        .append(" AS text), '')), lower(:")
                        .append(parameter)
                        .append(")) > 0");
                parameters.put(parameter, value);
            }
        }
        return new Clause(sql.toString(), Map.copyOf(parameters));
    }

    private record Boundary(String value, Instant instant, LocalDateTime local) {
        boolean after(Boundary other) {
            if ((instant == null) != (other.instant == null)) throw invalidRange();
            return instant == null ? local.isAfter(other.local) : instant.isAfter(other.instant);
        }
    }

    /** Accept ISO offsets and the local date/time strings emitted by ProTable's date formatter. */
    private static Boundary boundary(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip().replace(' ', 'T');
        try {
            var offset = OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC);
            if (offset.getYear() < 1 || offset.getYear() > 9999) throw invalidRange();
            return new Boundary(offset.toString(), offset.toInstant(), null);
        } catch (DateTimeParseException ignored) {
            try {
                var local =
                        text.length() == 10
                                ? LocalDate.parse(text).atStartOfDay()
                                : LocalDateTime.parse(text);
                if (local.getYear() < 1 || local.getYear() > 9999) throw invalidRange();
                return new Boundary(local.toString(), null, local);
            } catch (DateTimeParseException invalid) {
                throw invalidRange();
            }
        }
    }

    private static ResponseStatusException invalidRange() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid list filter range");
    }
}
