package com.lumira.platform.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.lumira.platform.common.PageRequest;
import com.lumira.plugins.deliveryconsole.DeliveryPageQuerySupport;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeliveryPageQuerySupportTest {
    @Test
    void keepsUnfilteredNullStateAndPaginationParameters() {
        var parameters = DeliveryPageQuerySupport.parameters(new PageRequest(3, 25, null), null);

        assertEquals("", parameters.filterSql());
        assertNull(parameters.countParameters().get("state"));
        assertEquals("", parameters.countParameters().get("search"));
        assertFalse(parameters.countParameters().containsKey("limit"));
        assertEquals(25, parameters.rowsParameters().get("limit"));
        assertEquals(50L, parameters.rowsParameters().get("offset"));
    }

    @Test
    void sharesFilterBindingsBetweenCountAndRowsWithoutSharingPagination() {
        var page =
                new PageRequest(
                        2,
                        10,
                        " failed ",
                        Map.of("lastResult", "timeout"),
                        Map.of("lastResult", "exact"));
        var parameters = DeliveryPageQuerySupport.parameters(page, "FAILED");

        assertEquals(" AND CAST(last_result AS text) = :list_filter_0", parameters.filterSql());
        assertEquals("FAILED", parameters.countParameters().get("state"));
        assertEquals("failed", parameters.countParameters().get("search"));
        assertEquals("timeout", parameters.countParameters().get("list_filter_0"));
        assertEquals(
                parameters.countParameters(),
                parameters.rowsParameters().entrySet().stream()
                        .filter(
                                entry ->
                                        !entry.getKey().equals("limit")
                                                && !entry.getKey().equals("offset"))
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey, Map.Entry::getValue)));
        assertEquals(10, parameters.rowsParameters().get("limit"));
        assertEquals(10L, parameters.rowsParameters().get("offset"));
    }
}
