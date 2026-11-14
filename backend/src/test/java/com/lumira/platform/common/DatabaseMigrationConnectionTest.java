package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DatabaseMigrationConnectionTest {
    @Test
    void productionRequiresSeparateCompleteCredentials() {
        var runtime = new DriverManagerDataSource();
        assertThrows(
                IllegalStateException.class,
                () ->
                        new DatabaseMigrationConnection(
                                runtime, "jdbc:postgresql://localhost/test", "", "", true));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new DatabaseMigrationConnection(
                                runtime,
                                "jdbc:postgresql://localhost/test",
                                "migration",
                                "",
                                false));
        assertSame(
                runtime,
                new DatabaseMigrationConnection(
                                runtime, "jdbc:postgresql://localhost/test", "", "", false)
                        .source());
        assertNotSame(
                runtime,
                new DatabaseMigrationConnection(
                                runtime,
                                "jdbc:postgresql://localhost/test",
                                "migration",
                                "fixture-only",
                                true)
                        .source());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
            named = "LUMIRA_TEST_DB_URL",
            matches = ".+")
    void inheritedHistoryWritesFailClosed() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String adminUser = System.getenv("LUMIRA_TEST_DB_USER");
        String adminPassword = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        var admin =
                new org.springframework.jdbc.core.JdbcTemplate(
                        new DriverManagerDataSource(url, adminUser, adminPassword));
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "");
        String role = "qa_history_" + suffix;
        String table = "qa_history_" + suffix;
        String password = java.util.UUID.randomUUID().toString();
        admin.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
        try {
            admin.execute("CREATE TABLE public." + table + "(id int)");
            var runtime = new DriverManagerDataSource(url, role, password);
            var migration =
                    new DatabaseMigrationConnection(runtime, url, adminUser, adminPassword, false);
            admin.execute("GRANT INSERT ON public." + table + " TO PUBLIC");
            assertThrows(IllegalStateException.class, () -> migration.protectHistory(table));
            admin.execute("REVOKE INSERT ON public." + table + " FROM PUBLIC");
            assertDoesNotThrow(() -> migration.protectHistory(table));
        } finally {
            admin.execute("DROP TABLE IF EXISTS public." + table);
            admin.execute("DROP ROLE " + role);
        }
    }
}
