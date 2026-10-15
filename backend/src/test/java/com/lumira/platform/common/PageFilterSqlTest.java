package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

class PageFilterSqlTest {
    private static final Map<String, String> COLUMNS =
            Map.of("createdAt", "created_at", "label", "label");

    @Test
    void rejectsInvalidDatesAndReversedRangesBeforeDatabaseQueries() {
        for (String value :
                java.util.List.of(
                        "not-a-date",
                        "2026-02-30",
                        "2026-10-15T25:00:00Z",
                        "infinity",
                        "0000-01-01")) {
            assertBadRequest(() -> build(Map.of("createdAt.from", value)));
        }
        assertBadRequest(
                () ->
                        build(
                                Map.of(
                                        "createdAt.from",
                                        "2026-10-16T00:00:00Z",
                                        "createdAt.to",
                                        "2026-10-15T00:00:00Z")));
        assertBadRequest(
                () ->
                        build(
                                Map.of(
                                        "createdAt.from",
                                        "2026-10-16 00:00:00",
                                        "createdAt.to",
                                        "2026-10-15 00:00:00")));
    }

    @Test
    void rejectsRangeModeForTextAndRequiresExplicitTemporalColumns() {
        var textRange =
                new PageRequest(
                        1, 20, "", Map.of("label.from", "2026-10-15"), Map.of("label", "range"));
        assertBadRequest(() -> PageFilterSql.build(textRange, COLUMNS, Set.of("createdAt")));
        var dateRange =
                new PageRequest(
                        1,
                        20,
                        "",
                        Map.of("createdAt.from", "2026-10-15"),
                        Map.of("createdAt", "range"));
        assertBadRequest(() -> PageFilterSql.build(dateRange, COLUMNS));
    }

    @Test
    void acceptsUiLocalDatesAndTimesAndNormalizesOffsets() {
        assertEquals(
                Map.of("list_filter_0", "2026-10-15T00:00"),
                build(Map.of("createdAt.from", "2026-10-15")).parameters());
        assertEquals(
                Map.of("list_filter_0", "2026-10-15T12:30:45"),
                build(Map.of("createdAt.from", "2026-10-15 12:30:45")).parameters());
        assertEquals(
                Map.of("list_filter_0", "2026-10-15T00:00Z"),
                build(Map.of("createdAt.from", "2026-10-15T08:00:00+08:00")).parameters());
        assertEquals("", build(Map.of()).sql());
        assertEquals(
                2,
                build(
                                Map.of(
                                        "createdAt.from",
                                        "2026-10-15T00:00:00Z",
                                        "createdAt.to",
                                        "2026-10-15T08:00:00+08:00"))
                        .parameters()
                        .size());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
    void validOffsetRangeFiltersActualPostgresTimestampsWithoutFixtureWrites() {
        var jdbc =
                JdbcClient.create(
                        new DriverManagerDataSource(
                                System.getenv("LUMIRA_TEST_DB_URL"),
                                System.getenv("LUMIRA_TEST_DB_USER"),
                                System.getenv("LUMIRA_TEST_DB_PASSWORD")));
        var filter =
                build(
                        Map.of(
                                "createdAt.from",
                                "2026-10-15T08:00:00+08:00",
                                "createdAt.to",
                                "2026-10-15T08:00:00+08:00"));
        long count =
                jdbc.sql(
                                "SELECT count(*) FROM (VALUES (TIMESTAMPTZ '2026-10-15"
                                        + " 00:00:00+00'),(TIMESTAMPTZ '2026-10-16 00:00:00+00')) AS"
                                        + " fixture(created_at) WHERE true"
                                        + filter.sql())
                        .params(filter.parameters())
                        .query(Long.class)
                        .single();
        assertEquals(1, count);
    }

    private static PageFilterSql.Clause build(Map<String, String> filters) {
        return PageFilterSql.build(
                new PageRequest(1, 20, "", filters, Map.of("createdAt", "range")),
                COLUMNS,
                Set.of("createdAt"));
    }

    private static void assertBadRequest(org.junit.jupiter.api.function.Executable operation) {
        assertEquals(
                400,
                assertThrows(ResponseStatusException.class, operation).getStatusCode().value());
    }
}
