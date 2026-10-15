package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

class PageRequestTest {
    @Test
    void rejectsInvalidBounds() {
        assertThrows(ResponseStatusException.class, () -> new PageRequest(0, 20, ""));
        assertThrows(ResponseStatusException.class, () -> new PageRequest(1, 0, ""));
        assertThrows(ResponseStatusException.class, () -> new PageRequest(1, 101, ""));
        assertThrows(ResponseStatusException.class, () -> new PageRequest(1, 20, "x".repeat(101)));
    }

    @Test
    void offsetDoesNotOverflowInteger() {
        assertEquals(214748364600L, new PageRequest(Integer.MAX_VALUE, 100, null).offset());
    }

    @Test
    void normalizesSearch() {
        assertEquals("admin", new PageRequest(1, 20, " admin ").search());
        assertEquals("", new PageRequest(1, 20, null).search());
    }

    @Test
    void rejectsNullFilterKeysAndValuesAsClientErrors() {
        var nullKey = new LinkedHashMap<String, String>();
        nullKey.put(null, "value");
        var nullValue = new LinkedHashMap<String, String>();
        nullValue.put("name", null);
        for (var invalid : java.util.List.of(nullKey, nullValue)) {
            assertEquals(
                    400,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () -> new PageRequest(1, 20, "", invalid, Map.of()))
                            .getStatusCode()
                            .value());
            assertEquals(
                    400,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () -> new PageRequest(1, 20, "", Map.of(), invalid))
                            .getStatusCode()
                            .value());
        }
    }

    @Test
    void retainsAnImmutableSnapshotOfValidatedFilters() {
        var filters = new LinkedHashMap<>(Map.of("name", "admin"));
        var request = new PageRequest(1, 20, "", filters, Map.of("name", "exact"));
        filters.put("name", "changed");
        assertEquals("admin", request.filters().get("name"));
        assertThrows(UnsupportedOperationException.class, () -> request.filters().put("name", "x"));
    }

    @Test
    void rejectsNulInSearchAndFilterValuesBeforeSqlBinding() {
        assertThrows(ResponseStatusException.class, () -> new PageRequest(1, 20, "a\0b"));
        assertThrows(
                ResponseStatusException.class,
                () -> new PageRequest(1, 20, "", Map.of("name", "a\0b"), Map.of()));
    }
}
