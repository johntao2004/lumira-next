package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "LUMIRA_PLUGIN_INITIALIZATION_TEST_DB_URL", matches = ".+")
class PluginInitializationDatabaseTest {
    private static final List<String> IDS =
            List.of(
                    "payment-core",
                    "wechat-pay",
                    "alipay-pay",
                    "mfa",
                    "feedback",
                    "localization",
                    "sensitive-words");

    @Test
    void initializesOncePreservesPublishedDataAndHistoryAndRejectsTampering(@TempDir Path tampered)
            throws Exception {
        var source =
                new DriverManagerDataSource(
                        System.getenv("LUMIRA_PLUGIN_INITIALIZATION_TEST_DB_URL"),
                        System.getenv("LUMIRA_PLUGIN_INITIALIZATION_TEST_DB_USER"),
                        System.getenv("LUMIRA_PLUGIN_INITIALIZATION_TEST_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        assertTrue(
                jdbc.queryForObject("SELECT current_database()", String.class)
                        .startsWith("lumira_plugin_init_partial_qa_"));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_tables WHERE schemaname='public'", Integer.class));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        for (String id : IDS) {
            var directory = root().resolve("plugins/" + id + "/migrations");
            PluginPermissionMigrationRunner.migrate(source, id, directory);
            assertEquals(
                    2,
                    jdbc.queryForObject("SELECT count(*) FROM " + historyTable(id), Integer.class));
        }
        jdbc.execute(
                "INSERT INTO platform_user(id,username,display_name,password_hash) VALUES ('90000000-0000-0000-0000-000000000001','plugin-initialization-fixture','Fixture','not-a-login-credential')");
        jdbc.execute(
                "INSERT INTO plugin_mfa_authenticator(user_id,label,secret_envelope,confirmed,last_step) VALUES ('90000000-0000-0000-0000-000000000001','Fixture MFA','fixture-envelope',true,500)");
        jdbc.execute(
                "INSERT INTO plugin_mfa_recovery(user_id,digest) VALUES ('90000000-0000-0000-0000-000000000001',repeat('a',64))");
        jdbc.execute(
                "INSERT INTO plugin_payment_core_settings(provider_id,current_version,values_json,updated_by) VALUES ('wechat-pay',1,'{\"fixture\":true}','fixture')");
        var initialized = fingerprints(jdbc);
        for (String id : IDS)
            PluginPermissionMigrationRunner.migrate(
                    source, id, root().resolve("plugins/" + id + "/migrations"));
        assertEquals(initialized, fingerprints(jdbc));

        var directory = root().resolve("plugins/mfa/migrations");
        var catalog = PluginInitializationMigrationRunner.readCatalog("mfa", directory);
        Files.copy(
                directory.resolve("initialization.json"), tampered.resolve("initialization.json"));
        Files.writeString(
                tampered.resolve(catalog.script()),
                Files.readString(directory.resolve(catalog.script())) + "\nSELECT 1;\n");
        assertThrows(
                RuntimeException.class,
                () -> PluginPermissionMigrationRunner.migrate(source, "mfa", tampered));
        assertEquals(initialized, fingerprints(jdbc));

        // Simulate immutable, fully completed published histories on the same final schema.
        for (String id : IDS) {
            var metadata =
                    PluginInitializationMigrationRunner.readCatalog(
                            id, root().resolve("plugins/" + id + "/migrations"));
            jdbc.update("DELETE FROM " + historyTable(id) + " WHERE type='SQL'");
            int rank = 2;
            for (var entry : metadata.publishedMigrations())
                jdbc.update(
                        "INSERT INTO "
                                + historyTable(id)
                                + " (installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) VALUES (?,?,?,'SQL',?,?,current_user,0,true)",
                        rank++,
                        entry.version(),
                        entry.description(),
                        entry.script(),
                        entry.checksum());
        }
        var published = fingerprints(jdbc);
        for (int pass = 0; pass < 2; pass++)
            for (String id : IDS)
                PluginPermissionMigrationRunner.migrate(
                        source, id, root().resolve("plugins/" + id + "/migrations"));
        assertEquals(published, fingerprints(jdbc));

        jdbc.update("UPDATE plugin_mfa_schema_history SET checksum=checksum+1 WHERE version='1'");
        var changed = fingerprints(jdbc);
        assertThrows(
                IllegalStateException.class,
                () -> PluginPermissionMigrationRunner.migrate(source, "mfa", directory));
        assertEquals(changed, fingerprints(jdbc));
    }

    private static String historyTable(String id) {
        return "plugin_" + id.replace('-', '_') + "_schema_history";
    }

    private static Map<String, String> fingerprints(JdbcTemplate jdbc) {
        var result = new TreeMap<String, String>();
        for (String table :
                jdbc.queryForList(
                        "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",
                        String.class)) {
            if (!table.matches("[a-z_]+")) throw new IllegalStateException("Unexpected test table");
            result.put(
                    table,
                    jdbc.queryForObject(
                            "SELECT md5(coalesce(string_agg(hash,'' ORDER BY hash),'')) FROM (SELECT md5(to_jsonb(t)::text) hash FROM public."
                                    + table
                                    + " t) rows",
                            String.class));
        }
        return result;
    }

    private static Path root() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent())
            if (Files.isDirectory(path.resolve("plugins/mfa/migrations"))) return path;
        throw new IllegalStateException("Plugin sources not found");
    }
}
