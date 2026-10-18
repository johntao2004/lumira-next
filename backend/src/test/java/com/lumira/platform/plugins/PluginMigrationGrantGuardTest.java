package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginMigrationGrantGuardTest {
    @Test
    void bundledGrantScriptsAreExactlyTheAllowlistAndOtherWritesAreRejected(@TempDir Path directory)
            throws Exception {
        Path plugins = repositoryRoot().resolve("plugins");
        var mutating = new HashSet<String>();
        try (Stream<Path> files = Files.walk(plugins)) {
            for (Path sql :
                    files.filter(path -> path.getFileName().toString().endsWith(".sql")).toList()) {
                byte[] bytes = Files.readAllBytes(sql);
                if (PluginMigrationGrantGuard.mutatesCoreGrantTables(
                        Files.readString(sql)))
                    mutating.add(PluginMigrationGrantGuard.sha256(bytes));
                PluginMigrationGrantGuard.rejectUntrustedMutations(sql.getParent());
            }
        }
        assertEquals(PluginMigrationGrantGuard.BUNDLED_GRANT_SCRIPT_DIGESTS, mutating);

        assertFalse(
                PluginMigrationGrantGuard.mutatesCoreGrantTables(
                        "SELECT authority FROM platform_role_authority WHERE role_id = '1';"));
        assertTrue(
                PluginMigrationGrantGuard.mutatesCoreGrantTables(
                        "INSERT INTO platform_role_authority(role_id, authority) VALUES ('1','x');"));
        assertTrue(
                PluginMigrationGrantGuard.mutatesCoreGrantTables(
                        "SELECT format('INSERT INTO %s VALUES (1)', 'platform_role_authority');"));

        Files.writeString(
                directory.resolve("V9__escalate.sql"),
                "INSERT INTO platform_authority(code) VALUES ('platform:roles:create');\n");
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> PluginMigrationGrantGuard.rejectUntrustedMutations(directory));
        assertTrue(failure.getMessage().contains("pluginMigrationGrantMutationDenied"));
    }

    private static Path repositoryRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isDirectory(cwd.resolve("plugins"))) return cwd;
        if (cwd.getParent() != null && Files.isDirectory(cwd.getParent().resolve("plugins")))
            return cwd.getParent();
        throw new IllegalStateException("plugins directory not found from " + cwd);
    }
}
