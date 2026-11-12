package com.lumira.platform.plugins;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.executor.Context;
import org.flywaydb.core.api.executor.MigrationExecutor;
import org.flywaydb.core.api.resolver.ResolvedMigration;
import org.flywaydb.core.internal.jdbc.Results;
import org.flywaydb.core.internal.resolver.ResolvedMigrationImpl;

/** Appends explicit forward changes while retaining every previously applied history row. */
final class PluginEvolutionMigrationRunner {
    private PluginEvolutionMigrationRunner() {}

    static int checksum(String sql) {
        var crc = new CRC32();
        crc.update(sql.getBytes(StandardCharsets.UTF_8));
        return (int) crc.getValue();
    }

    static void validateCatalog(String id, PluginInitializationMigrationRunner.Catalog catalog) {
        int legacy = catalog.publishedMigrations().size();
        int terminal = Integer.parseInt(catalog.version());
        if (legacy == 0
                || catalog.evolutions().isEmpty()
                || terminal != legacy + catalog.evolutions().size()) throw invalid(id);
        var snapshots = new HashSet<String>();
        for (var previous : catalog.previousInitializations()) {
            validateMetadata(id, previous);
            int version = Integer.parseInt(previous.version());
            if (version < legacy
                    || version >= terminal
                    || !snapshots.add(previous.version())
                    || !previous.script().equals("V" + version + "__plugin_initialization.sql"))
                throw invalid(id);
        }
        if (!snapshots.contains(Integer.toString(legacy))) throw invalid(id);
        for (int i = 0; i < catalog.evolutions().size(); i++) {
            var evolution = catalog.evolutions().get(i);
            if (evolution == null) throw invalid(id);
            validateMetadata(id, evolution.migration());
            if (!Integer.toString(legacy + i + 1).equals(evolution.migration().version())
                    || evolution.migration().script().equals(catalog.script())
                    || evolution.sql() == null
                    || evolution.sql().isBlank()
                    || evolution.sql().length() > 1_048_576
                    || evolution.migration().checksum() != checksum(evolution.sql()))
                throw invalid(id);
        }
    }

    private static void validateMetadata(
            String id, PluginInitializationMigrationRunner.PublishedMigration value) {
        if (value == null
                || value.version() == null
                || !value.version().matches("[1-9][0-9]*")
                || value.description() == null
                || value.description().isBlank()
                || value.checksum() == null
                || value.script() == null
                || !value.script().matches("V" + value.version() + "__[A-Za-z0-9_]+[.]sql"))
            throw invalid(id);
    }

    static void migrate(
            String id,
            PluginInitializationMigrationRunner.Catalog catalog,
            List<Map<String, Object>> history,
            FluentConfiguration configuration) {
        var applied = new ArrayList<Map<String, Object>>();
        for (var row : history) {
            if (!Boolean.TRUE.equals(row.get("success"))) throw mismatch(id);
            if ("SCHEMA".equals(row.get("type"))) continue;
            if ("BASELINE".equals(row.get("type")) && "0".equals(row.get("version"))) continue;
            if (!"SQL".equals(row.get("type"))) throw mismatch(id);
            applied.add(row);
        }
        if (applied.isEmpty()) {
            configuration.load().migrate();
            return;
        }
        if (catalog.script().equals(applied.getFirst().get("script"))) {
            if (applied.size() != 1 || !catalog.version().equals(applied.getFirst().get("version")))
                throw mismatch(id);
            // The current full SQL remains validated by Flyway's actual file checksum.
            configuration.load().validate();
            return;
        }
        var resolved = new ArrayList<ResolvedMigration>();
        var snapshot =
                catalog.previousInitializations().stream()
                        .filter(value -> value.script().equals(applied.getFirst().get("script")))
                        .findFirst();
        int baseVersion;
        int consumed;
        if (snapshot.isPresent()) {
            requireExact(id, snapshot.get(), applied.getFirst());
            resolved.add(
                    resolve(
                            id,
                            snapshot.get(),
                            PluginInitializationMigrationRunner.VALIDATION_ONLY));
            baseVersion = Integer.parseInt(snapshot.get().version());
            consumed = 1;
        } else {
            if (applied.size() < catalog.publishedMigrations().size()) throw mismatch(id);
            consumed = 0;
            for (var published : catalog.publishedMigrations()) {
                requireExact(id, published, applied.get(consumed++));
                resolved.add(
                        resolve(
                                id,
                                published,
                                PluginInitializationMigrationRunner.VALIDATION_ONLY));
            }
            baseVersion = catalog.publishedMigrations().size();
        }
        for (var evolution : catalog.evolutions()) {
            if (Integer.parseInt(evolution.migration().version()) <= baseVersion) continue;
            if (consumed < applied.size())
                requireExact(id, evolution.migration(), applied.get(consumed++));
            resolved.add(resolve(id, evolution.migration(), new SqlEvolution(evolution.sql())));
        }
        if (consumed != applied.size()) throw mismatch(id);
        configuration
                .skipDefaultResolvers(true)
                .resolvers(context -> List.copyOf(resolved))
                .load()
                .migrate();
    }

    private static void requireExact(
            String id,
            PluginInitializationMigrationRunner.PublishedMigration expected,
            Map<String, Object> actual) {
        if (!Objects.equals(expected.version(), actual.get("version"))
                || !Objects.equals(expected.script(), actual.get("script"))
                || !Objects.equals(expected.description(), actual.get("description"))
                || !Objects.equals(expected.checksum(), actual.get("checksum"))) throw mismatch(id);
    }

    private static ResolvedMigration resolve(
            String id,
            PluginInitializationMigrationRunner.PublishedMigration metadata,
            MigrationExecutor executor) {
        return new ResolvedMigrationImpl(
                MigrationVersion.fromVersion(metadata.version()),
                metadata.description(),
                metadata.script(),
                metadata.checksum(),
                null,
                CoreMigrationType.SQL,
                "plugin-evolution:" + id + "/" + metadata.script(),
                executor);
    }

    private record SqlEvolution(String sql) implements MigrationExecutor {
        @Override
        public List<Results> execute(Context context) throws SQLException {
            try (var statement = context.getConnection().createStatement()) {
                statement.execute(sql);
            }
            return List.of();
        }

        @Override
        public boolean canExecuteInTransaction() {
            return true;
        }

        @Override
        public boolean shouldExecute() {
            return true;
        }
    }

    private static IllegalStateException invalid(String id) {
        return new IllegalStateException("invalidPluginEvolutionCatalog:" + id);
    }

    private static IllegalStateException mismatch(String id) {
        return new IllegalStateException("publishedPluginHistoryMismatch:" + id);
    }
}
