package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PluginPermissionMigrationRunnerTest {
    @Test
    void limitsPostV51NoOpsToPublishedPermissionOnlyScripts() {
        assertEquals(
                new PluginPermissionMigrationRunner.LegacyPermissionMigration(
                        "2", "1", "V2__independent_page_operations.sql", true),
                PluginPermissionMigrationRunner.noOpMigrationFor("feedback").orElseThrow());
        assertEquals(
                new PluginPermissionMigrationRunner.LegacyPermissionMigration(
                        "2", "1", "V2__independent_page_operations.sql", true),
                PluginPermissionMigrationRunner.noOpMigrationFor("localization").orElseThrow());
        assertEquals(
                new PluginPermissionMigrationRunner.LegacyPermissionMigration(
                        "2", "1", "V2__independent_page_operations.sql", true),
                PluginPermissionMigrationRunner.noOpMigrationFor("sensitive-words").orElseThrow());
        assertEquals(
                new PluginPermissionMigrationRunner.LegacyPermissionMigration(
                        "6", "5", "V6__fine_grained_admin_permissions.sql", false),
                PluginPermissionMigrationRunner.noOpMigrationFor("mfa").orElseThrow());
        assertTrue(PluginPermissionMigrationRunner.noOpMigrationFor("payment-core").isEmpty());
    }

    @Test
    void skipsPublishedPermissionScriptsOnlyWhenTheirOldSemanticsWouldBeUnsafe() {
        for (String pluginId : new String[] {"feedback", "localization", "sensitive-words"}) {
            assertTrue(PluginPermissionMigrationRunner.shouldUseNoOp(pluginId, true, false));
            assertFalse(PluginPermissionMigrationRunner.shouldUseNoOp(pluginId, true, true));
            assertFalse(PluginPermissionMigrationRunner.shouldUseNoOp(pluginId, false, false));
        }

        // V6 includes a bootstrap role grant, so it stays inert whenever first seen after host V51.
        assertTrue(PluginPermissionMigrationRunner.shouldUseNoOp("mfa", true, false));
        assertTrue(PluginPermissionMigrationRunner.shouldUseNoOp("mfa", true, true));
        assertFalse(PluginPermissionMigrationRunner.shouldUseNoOp("mfa", false, false));
        assertFalse(PluginPermissionMigrationRunner.shouldUseNoOp("payment-core", true, false));
    }

    @Test
    void consolidatedPackagesUseOneInitializationInsteadOfPublishedFinalizerFiles() {
        Path pluginRoot = findRepositoryRoot().resolve("plugins");
        assertTrue(
                PluginInitializationMigrationRunner.supports(
                        pluginRoot.resolve("feedback/migrations")));
        assertTrue(
                PluginInitializationMigrationRunner.supports(pluginRoot.resolve("mfa/migrations")));
        assertFalse(
                PluginPermissionMigrationRunner.requiresV51(
                        "diffuse-avatar", pluginRoot.resolve("diffuse-avatar/migrations")));
    }

    private static Path findRepositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isDirectory(directory.resolve("plugins/mfa/migrations"))) return directory;
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "Plugin migrations were not found from the test working directory.");
    }
}
