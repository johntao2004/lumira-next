package com.lumira.platform.communication;

import com.lumira.platform.common.PageFilterSql;
import com.lumira.platform.common.PageRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Shared filtering and pagination parameters for the mail and SMS delivery queues. */
public final class DeliveryPageQuerySupport {
    private static final Map<String, String> FILTERABLE_COLUMNS =
            Map.of(
                    "id",
                    "id",
                    "state",
                    "state",
                    "attempts",
                    "attempts",
                    "lastResult",
                    "last_result",
                    "createdAt",
                    "created_at",
                    "updatedAt",
                    "updated_at",
                    "availableAt",
                    "available_at",
                    "expiresAt",
                    "expires_at");

    private DeliveryPageQuerySupport() {}

    public static Parameters parameters(PageRequest page, String state) {
        var fieldFilter = PageFilterSql.build(page, FILTERABLE_COLUMNS);
        var countParameters = new HashMap<String, Object>();
        countParameters.put("state", state);
        countParameters.put("search", page.search());
        countParameters.putAll(fieldFilter.parameters());
        var rowsParameters = new HashMap<>(countParameters);
        rowsParameters.put("limit", page.pageSize());
        rowsParameters.put("offset", page.offset());
        return new Parameters(
                fieldFilter.sql(),
                Collections.unmodifiableMap(new HashMap<>(countParameters)),
                Collections.unmodifiableMap(new HashMap<>(rowsParameters)));
    }

    public record Parameters(
            String filterSql,
            Map<String, Object> countParameters,
            Map<String, Object> rowsParameters) {}
}
