package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PluginInitializationMigrationRunnerTest {
    private static Path root() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent())
            if (Files.isDirectory(path.resolve("plugins/mfa/migrations"))) return path;
        throw new IllegalStateException("Plugin sources not found");
    }

    @Test
    void eachBundledPluginAndExampleContainsOneFinalInitializationAndMetadataOnlyHistory()
            throws Exception {
        int count = 0;
        for (String relative :
                List.of(
                        "alipay-pay",
                        "feedback",
                        "localization",
                        "mfa",
                        "payment-core",
                        "sensitive-words",
                        "wechat-pay",
                        "sdk/examples/runtime-observer",
                        "sdk/examples/verification-debugger")) {
            var directory = root().resolve("plugins/" + relative + "/migrations");
            String id = directory.getParent().getFileName().toString();
            var catalog = PluginInitializationMigrationRunner.readCatalog(id, directory);
            assertTrue(
                    Files.readString(directory.resolve(catalog.script()))
                            .contains("initialization"));
            count++;
        }
        assertEquals(9, count);
    }

    @Test
    void completedPublishedHistoriesResolveForValidationWithExactOriginalIdentities()
            throws Exception {
        for (String id :
                List.of(
                        "feedback",
                        "localization",
                        "mfa",
                        "payment-core",
                        "sensitive-words",
                        "wechat-pay",
                        "alipay-pay")) {
            var catalog =
                    PluginInitializationMigrationRunner.readCatalog(
                            id, root().resolve("plugins/" + id + "/migrations"));
            var history = history(catalog);
            var resolved =
                    PluginInitializationMigrationRunner.validatePublishedHistory(
                            id, catalog, history);
            assertEquals(catalog.publishedMigrations().size(), resolved.size());
            for (int i = 0; i < resolved.size(); i++) {
                var actual = resolved.get(i);
                var expected = catalog.publishedMigrations().get(i);
                assertEquals(expected.version(), actual.getVersion().getVersion());
                assertEquals(expected.checksum(), actual.getChecksum());
                assertEquals(expected.script(), actual.getScript());
                assertEquals(expected.description(), actual.getDescription());
                assertThrows(java.sql.SQLException.class, () -> actual.getExecutor().execute(null));
            }
        }
    }

    @Test
    void newInitializationAndEmptyHistoryUseNormalSqlValidation() {
        var catalog =
                PluginInitializationMigrationRunner.readCatalog(
                        "mfa", root().resolve("plugins/mfa/migrations"));
        assertTrue(
                PluginInitializationMigrationRunner.validatePublishedHistory(
                                "mfa", catalog, List.of())
                        .isEmpty());
        assertTrue(
                PluginInitializationMigrationRunner.validatePublishedHistory(
                                "mfa",
                                catalog,
                                List.of(
                                        Map.of(
                                                "version",
                                                "7",
                                                "script",
                                                catalog.script(),
                                                "type",
                                                "SQL",
                                                "success",
                                                true)))
                        .isEmpty());
    }

    @Test
    void incompleteFailedUnknownChangedOrMixedHistoriesFailBeforeAnySqlCanRun() {
        var catalog =
                PluginInitializationMigrationRunner.readCatalog(
                        "mfa", root().resolve("plugins/mfa/migrations"));
        var partial = history(catalog);
        partial.removeLast();
        assertThrows(
                IllegalStateException.class,
                () ->
                        PluginInitializationMigrationRunner.validatePublishedHistory(
                                "mfa", catalog, partial));
        for (var mutation :
                List.of(
                        Map.<String, Object>of("checksum", 123),
                        Map.<String, Object>of("description", "changed"),
                        Map.<String, Object>of("script", "V1__modified.sql"),
                        Map.<String, Object>of("version", "99"),
                        Map.<String, Object>of("success", false))) {
            var history = history(catalog);
            history.getFirst().putAll(mutation);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            PluginInitializationMigrationRunner.validatePublishedHistory(
                                    "mfa", catalog, history));
        }
        var mixed = history(catalog);
        mixed.add(
                Map.of("version", "7", "script", catalog.script(), "type", "SQL", "success", true));
        assertThrows(
                IllegalStateException.class,
                () ->
                        PluginInitializationMigrationRunner.validatePublishedHistory(
                                "mfa", catalog, mixed));
    }

    private static ArrayList<Map<String, Object>> history(
            PluginInitializationMigrationRunner.Catalog catalog) {
        var rows = new ArrayList<Map<String, Object>>();
        for (var entry : catalog.publishedMigrations())
            rows.add(
                    new LinkedHashMap<>(
                            Map.of(
                                    "version",
                                    entry.version(),
                                    "description",
                                    entry.description(),
                                    "script",
                                    entry.script(),
                                    "checksum",
                                    entry.checksum(),
                                    "type",
                                    "SQL",
                                    "success",
                                    true)));
        return rows;
    }
}
