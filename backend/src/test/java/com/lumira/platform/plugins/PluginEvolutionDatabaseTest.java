package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "LUMIRA_PLUGIN_EVOLUTION_TEST_DB_URL", matches = ".+")
class PluginEvolutionDatabaseTest {
    @Test
    void upgradesBothPublishedHistoriesWithoutRewritingRowsAndRollsBackFailedEvolution(
            @TempDir Path temporary) throws Exception {
        var source =
                new DriverManagerDataSource(
                        System.getenv("LUMIRA_PLUGIN_EVOLUTION_TEST_DB_URL"),
                        System.getenv("LUMIRA_PLUGIN_EVOLUTION_TEST_DB_USER"),
                        System.getenv("LUMIRA_PLUGIN_EVOLUTION_TEST_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        assertTrue(
                jdbc.queryForObject("SELECT current_database()", String.class)
                        .startsWith("lumira_plugin_evolution_qa_"));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_tables WHERE schemaname='public'", Integer.class));
        Path current = directory();
        var mapper = new ObjectMapper();
        var catalog = PluginInitializationMigrationRunner.readCatalog("payment-core", current);
        var json =
                mapper.readValue(
                        Files.readString(current.resolve("initialization.json")),
                        new TypeReference<LinkedHashMap<String, Object>>() {});
        Path old = Files.createDirectory(temporary.resolve("old"));
        var oldJson = new LinkedHashMap<>(json);
        oldJson.put("formatVersion", 1);
        oldJson.put("version", "4");
        oldJson.put("script", "V4__plugin_initialization.sql");
        oldJson.remove("previousInitializations");
        oldJson.remove("evolutions");
        Files.writeString(old.resolve("initialization.json"), mapper.writeValueAsString(oldJson));
        // The prior-shape fixture is derived from the current final initialization, not an archived
        // migration tree.
        Files.writeString(
                old.resolve("V4__plugin_initialization.sql"),
                Files.readString(current.resolve(catalog.script()))
                        .replace(
                                "CHECK (provider_id ~ '^[a-z][a-z0-9-]{0,31}$')",
                                "CHECK (provider_id IN ('wechat-pay','alipay-pay'))"));
        try {
            Flyway.configure()
                    .dataSource(source)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            PluginInitializationMigrationRunner.migrate(source, "payment-core", old);
            assertEquals(
                    catalog.previousInitializations().getFirst().checksum(),
                    jdbc.queryForObject(
                            "SELECT checksum FROM plugin_payment_core_schema_history WHERE version='4'",
                            Integer.class));
            seed(jdbc, "wechat-pay", "existing");
            var beforeHistory = history(jdbc);
            var beforeData = data(jdbc);
            assertThrows(RuntimeException.class, () -> seed(jdbc, "third-pay", "not-yet"));

            Path broken = Files.createDirectory(temporary.resolve("broken"));
            Files.copy(current.resolve(catalog.script()), broken.resolve(catalog.script()));
            String brokenSql = catalog.evolutions().getFirst().sql() + "SELECT 1/0;\n";
            var metadata = catalog.evolutions().getFirst().migration();
            var brokenJson = new LinkedHashMap<>(json);
            brokenJson.put(
                    "evolutions",
                    List.of(
                            new PluginInitializationMigrationRunner.Evolution(
                                    new PluginInitializationMigrationRunner.PublishedMigration(
                                            metadata.version(),
                                            metadata.description(),
                                            metadata.script(),
                                            PluginEvolutionMigrationRunner.checksum(brokenSql)),
                                    brokenSql)));
            Files.writeString(
                    broken.resolve("initialization.json"), mapper.writeValueAsString(brokenJson));
            assertThrows(
                    RuntimeException.class,
                    () ->
                            PluginInitializationMigrationRunner.migrate(
                                    source, "payment-core", broken));
            assertEquals(beforeHistory, history(jdbc));
            assertEquals(beforeData, data(jdbc));
            assertThrows(RuntimeException.class, () -> seed(jdbc, "third-pay", "still-old"));

            PluginInitializationMigrationRunner.migrate(source, "payment-core", current);
            assertEquals(beforeHistory, history(jdbc).subList(0, beforeHistory.size()));
            assertEquals(beforeHistory.size() + 1, history(jdbc).size());
            assertEquals(beforeData, data(jdbc));
            seed(jdbc, "third-pay", "new-provider");
            var upgradedHistory = history(jdbc);
            var upgradedData = data(jdbc);
            PluginInitializationMigrationRunner.migrate(source, "payment-core", current);
            assertEquals(upgradedHistory, history(jdbc));
            assertEquals(upgradedData, data(jdbc));
            assertThrows(
                    RuntimeException.class,
                    () ->
                            PluginInitializationMigrationRunner.migrate(
                                    source, "payment-core", broken));
            assertEquals(upgradedHistory, history(jdbc));

            // A completed original incremental history is another supported starting identity.
            jdbc.execute("DELETE FROM plugin_payment_core_schema_history WHERE type='SQL'");
            int rank = 2;
            for (var entry : catalog.publishedMigrations())
                jdbc.update(
                        "INSERT INTO plugin_payment_core_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) VALUES(?,?,?,'SQL',?,?,current_user,0,true)",
                        rank++,
                        entry.version(),
                        entry.description(),
                        entry.script(),
                        entry.checksum());
            var legacyHistory = history(jdbc);
            PluginInitializationMigrationRunner.migrate(source, "payment-core", current);
            assertEquals(legacyHistory, history(jdbc).subList(0, legacyHistory.size()));
            assertEquals(upgradedData, data(jdbc));
            var evolvedConstraints = constraints(jdbc);

            jdbc.execute("DROP SCHEMA public CASCADE");
            jdbc.execute("CREATE SCHEMA public");
            Flyway.configure()
                    .dataSource(source)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            PluginInitializationMigrationRunner.migrate(source, "payment-core", current);
            assertEquals(
                    List.of(catalog.script()),
                    jdbc.queryForList(
                            "SELECT script FROM plugin_payment_core_schema_history WHERE type='SQL'",
                            String.class));
            assertEquals(evolvedConstraints, constraints(jdbc));
            seed(jdbc, "third-pay", "fresh");
            var freshHistory = history(jdbc);
            PluginInitializationMigrationRunner.migrate(source, "payment-core", current);
            assertEquals(freshHistory, history(jdbc));
        } finally {
            jdbc.execute("DROP SCHEMA public CASCADE");
            jdbc.execute("CREATE SCHEMA public");
        }
    }

    private static void seed(JdbcTemplate jdbc, String provider, String tag) {
        // One statement makes a rejected provider atomic across settings, history, and orders.
        jdbc.update(
                "WITH setting AS (INSERT INTO plugin_payment_core_settings(provider_id,current_version,values_json,updated_by) VALUES (?,1,'{}',?) RETURNING provider_id), snapshot AS (INSERT INTO plugin_payment_core_settings_history(provider_id,version,values_json,created_by) SELECT provider_id,1,'{}',? FROM setting RETURNING provider_id) INSERT INTO plugin_payment_core_order(order_id,idempotency_key,request_fingerprint,provider_id,settings_version,merchant_order_no,amount_fen,subject,status,created_by) SELECT gen_random_uuid(),gen_random_uuid(),repeat('a',64),provider_id,1,?,100,?,'PENDING',? FROM snapshot",
                provider,
                tag,
                tag,
                tag,
                tag,
                tag);
    }

    private static List<Map<String, Object>> history(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT * FROM plugin_payment_core_schema_history ORDER BY installed_rank");
    }

    private static List<String> data(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT row_to_json(t)::text FROM plugin_payment_core_settings t UNION ALL SELECT row_to_json(t)::text FROM plugin_payment_core_settings_history t UNION ALL SELECT row_to_json(t)::text FROM plugin_payment_core_order t ORDER BY 1",
                String.class);
    }

    private static List<String> constraints(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT conname || ':' || pg_get_constraintdef(oid) FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname LIKE 'plugin_payment_core%provider_id_check' ORDER BY conname",
                String.class);
    }

    private static Path directory() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("plugins/payment-core")))
            root = root.getParent();
        if (root == null) throw new IllegalStateException("Plugin sources not found");
        return root.resolve("plugins/payment-core/migrations");
    }
}
