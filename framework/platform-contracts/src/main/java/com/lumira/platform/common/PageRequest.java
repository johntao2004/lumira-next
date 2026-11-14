package com.lumira.platform.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 不可变列表请求参数及过滤解析，校验分页边界。 Immutable list request and filter parsing with pagination validation. */
public record PageRequest(
        int page,
        int pageSize,
        String search,
        Map<String, String> filters,
        Map<String, String> filterModes) {
    private static final Pattern FILTER_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,100}");

    public PageRequest(int page, int pageSize, String search) {
        this(page, pageSize, search, Map.of(), Map.of());
    }

    public PageRequest {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination");
        }
        search = search == null ? "" : search.strip();
        if (search.length() > 100 || search.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search is too long");
        }
        filters = filters == null ? Map.of() : new LinkedHashMap<>(filters);
        filterModes = filterModes == null ? Map.of() : new LinkedHashMap<>(filterModes);
        if (filters.size() > 40 || filterModes.size() > 40) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many list filters");
        }
        for (var entry : filters.entrySet()) {
            if (entry.getKey() == null
                    || !FILTER_NAME.matcher(entry.getKey()).matches()
                    || entry.getValue() == null
                    || entry.getValue().indexOf('\0') >= 0
                    || entry.getValue().length() > 512) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid list filter");
            }
        }
        for (var entry : filterModes.entrySet()) {
            if (entry.getKey() == null
                    || !FILTER_NAME.matcher(entry.getKey()).matches()
                    || entry.getValue() == null
                    || !SetHolder.MODES.contains(entry.getValue())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Invalid list filter mode");
            }
        }
        filters = Map.copyOf(filters);
        filterModes = Map.copyOf(filterModes);
    }

    /**
     * 解析资源列表共用的 filter.* 和 filterMode.* 参数。 Parse the `filter.*` and `filterMode.*` query
     * parameters shared by resource tables.
     */
    public static PageRequest fromQueryParameters(
            int page, int pageSize, String search, Map<String, String> parameters) {
        var filters = new LinkedHashMap<String, String>();
        var modes = new LinkedHashMap<String, String>();
        if (parameters != null)
            parameters.forEach(
                    (key, value) -> {
                        if (key.startsWith("filter."))
                            filters.put(key.substring("filter.".length()), value);
                        else if (key.startsWith("filterMode."))
                            modes.put(key.substring("filterMode.".length()), value);
                    });
        return new PageRequest(page, pageSize, search, filters, modes);
    }

    public long offset() {
        return (long) (page - 1) * pageSize;
    }

    private static final class SetHolder {
        private static final java.util.Set<String> MODES =
                java.util.Set.of("contains", "exact", "range");
    }
}
