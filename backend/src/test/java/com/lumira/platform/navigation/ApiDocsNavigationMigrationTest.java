package com.lumira.platform.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Applies the complete migration chain to an explicitly provisioned disposable database. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_MIGRATION_TEST_DB_URL", matches = ".+")
class ApiDocsNavigationMigrationTest {
    private static JdbcTemplate jdbc;
    private static Flyway flyway;

    @BeforeAll
    static void migrateFreshDatabase() {
        String url = System.getenv("LUMIRA_MIGRATION_TEST_DB_URL");
        String user = System.getenv("LUMIRA_MIGRATION_TEST_DB_USER");
        String password = System.getenv("LUMIRA_MIGRATION_TEST_DB_PASSWORD");
        var dataSource = new DriverManagerDataSource(url, user, password);
        jdbc = new JdbcTemplate(dataSource);

        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        assertTrue(
                database != null && database.startsWith("lumira_api_docs_qa_"),
                "Full migration tests require the dedicated lumira_api_docs_qa_* database.");
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables "
                                + "WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'",
                        Integer.class),
                "The dedicated migration-test database must be fresh.");

        flyway =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .schemas("public")
                        .defaultSchema("public")
                        .load();
        flyway.migrate();
    }

    @Test
    void fullMigrationChainKeepsExistingSeedsAndAddsGlobalMonitoringEntry() {
        assertTrue(
                flyway.info().applied().length >= 27,
                "The full versioned migration chain, including V27, should be applied.");

        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_navigation_menu "
                                + "WHERE menu_code = 'ai-management' "
                                + "AND id = '00000000-0000-0000-0000-000000000056'::uuid",
                        Integer.class),
                "The existing AI navigation seed must remain intact.");
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_navigation_menu "
                                + "WHERE menu_code = 'monitoring' "
                                + "AND path = '/dashboard/monitoring'",
                        Integer.class),
                "The existing monitoring entry must remain intact.");

        Map<String, Object> apiDocs =
                jdbc.queryForMap(
                        "SELECT child.id, child.path, child.permission_key, child.global_only, "
                                + "child.module_id, child.enabled, child.built_in, parent.menu_code AS parent_code "
                                + "FROM platform_navigation_menu child "
                                + "JOIN platform_navigation_menu parent ON parent.id = child.parent_id "
                                + "WHERE child.menu_code = 'api-docs'");
        assertNotNull(apiDocs.get("id"));
        assertEquals("/dashboard/api-docs", apiDocs.get("path"));
        assertEquals("platform:api-docs:access", apiDocs.get("permission_key"));
        assertEquals(true, apiDocs.get("global_only"));
        assertEquals("monitoring", apiDocs.get("module_id"));
        assertEquals(true, apiDocs.get("enabled"));
        assertEquals(true, apiDocs.get("built_in"));
        assertEquals("operations", apiDocs.get("parent_code"));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_navigation_menu "
                                + "WHERE id = '00000000-0000-0000-0000-000000000061'::uuid "
                                + "AND menu_code = 'api-docs'",
                        Integer.class));
    }
}
