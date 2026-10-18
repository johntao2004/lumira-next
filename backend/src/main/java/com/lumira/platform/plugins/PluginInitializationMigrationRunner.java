package com.lumira.platform.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.executor.Context;
import org.flywaydb.core.api.executor.MigrationExecutor;
import org.flywaydb.core.api.resolver.MigrationResolver;
import org.flywaydb.core.api.resolver.ResolvedMigration;
import org.flywaydb.core.internal.jdbc.Results;
import org.flywaydb.core.internal.resolver.ResolvedMigrationImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Installs final-state SQL without replaying or rewriting an existing plugin history. */
final class PluginInitializationMigrationRunner {
    static final String CATALOG = "initialization.json";

    record PublishedMigration(
            String version, String description, String script, Integer checksum) {}

    record Catalog(
            int formatVersion,
            String pluginId,
            String version,
            String script,
            List<PublishedMigration> publishedMigrations) {}

    private PluginInitializationMigrationRunner() {}

    static boolean supports(Path directory) {
        return Files.isRegularFile(directory.resolve(CATALOG));
    }

    static Catalog readCatalog(String pluginId, Path directory) {
        try {
            var catalog =
                    new ObjectMapper()
                            .readValue(directory.resolve(CATALOG).toFile(), Catalog.class);
            if (catalog == null
                    || catalog.formatVersion() != 1
                    || !pluginId.equals(catalog.pluginId())
                    || catalog.version() == null
                    || !catalog.version().matches("[1-9][0-9]*")
                    || !("V" + catalog.version() + "__plugin_initialization.sql")
                            .equals(catalog.script())
                    || catalog.publishedMigrations() == null) throw invalid(pluginId);
            try (var files = Files.list(directory)) {
                var sql =
                        files.filter(path -> path.getFileName().toString().endsWith(".sql"))
                                .toList();
                if (sql.size() != 1
                        || !sql.getFirst().getFileName().toString().equals(catalog.script())
                        || !Files.isRegularFile(sql.getFirst())) throw invalid(pluginId);
            }
            int terminal = Integer.parseInt(catalog.version());
            if (catalog.publishedMigrations().size() != terminal) throw invalid(pluginId);
            for (int index = 0; index < terminal; index++) {
                var entry = catalog.publishedMigrations().get(index);
                if (!Integer.toString(index + 1).equals(entry.version())
                        || entry.checksum() == null
                        || entry.description() == null
                        || entry.description().isBlank()
                        || entry.script() == null
                        || !entry.script().matches("V" + entry.version() + "__[A-Za-z0-9_]+[.]sql"))
                    throw invalid(pluginId);
            }
            return catalog;
        } catch (IOException | NumberFormatException error) {
            throw new IllegalStateException(
                    "invalidPluginInitializationCatalog:" + pluginId, error);
        }
    }

    static void migrate(DataSource source, String pluginId, Path directory) {
        if (!pluginId.matches("[a-z][a-z0-9-]{0,39}")) throw invalid(pluginId);
        var catalog = readCatalog(pluginId, directory);
        String table = "plugin_" + pluginId.replace('-', '_') + "_schema_history";
        var jdbc = new JdbcTemplate(source);
        List<Map<String, Object>> history =
                Boolean.TRUE.equals(
                                jdbc.queryForObject(
                                        "SELECT to_regclass(?) IS NOT NULL",
                                        Boolean.class,
                                        "public." + table))
                        ? jdbc.queryForList(
                                "SELECT version,description,type,script,checksum,success FROM public."
                                        + table
                                        + " ORDER BY installed_rank")
                        : List.of();
        var applied = validatePublishedHistory(pluginId, catalog, history);
        var configuration =
                Flyway.configure()
                        .dataSource(source)
                        .table(table)
                        .baselineOnMigrate(true)
                        .baselineVersion("0")
                        .locations("filesystem:" + directory.toAbsolutePath());
        if (!applied.isEmpty()) {
            configuration
                    .skipDefaultResolvers(true)
                    .resolvers(new PublishedHistoryResolver(applied))
                    .load()
                    .validate();
            return;
        }
        // Current installations validate the actual initialization SQL with the normal resolver.
        // Completed published installations instead validate metadata above, without executing SQL.
        configuration.load().migrate();
    }

    static List<ResolvedMigration> validatePublishedHistory(
            String pluginId, Catalog catalog, List<Map<String, Object>> history) {
        var published = new HashMap<String, PublishedMigration>();
        catalog.publishedMigrations().forEach(entry -> published.put(entry.version(), entry));
        var resolved = new ArrayList<ResolvedMigration>();
        var seen = new java.util.HashSet<String>();
        boolean initialized = false;
        for (var row : history) {
            if (!Boolean.TRUE.equals(row.get("success"))) throw historyMismatch(pluginId);
            String type = Objects.toString(row.get("type"), "");
            if ("BASELINE".equals(type) && "0".equals(row.get("version"))) continue;
            if ("SCHEMA".equals(type)) continue;
            if ("SQL".equals(type) && catalog.script().equals(row.get("script"))) {
                if (initialized || !catalog.version().equals(row.get("version")))
                    throw historyMismatch(pluginId);
                initialized = true;
                continue;
            }
            var entry = published.get(row.get("version"));
            if (entry == null
                    || !"SQL".equals(type)
                    || !seen.add(entry.version())
                    || !entry.description().equals(row.get("description"))
                    || !entry.script().equals(row.get("script"))
                    || !entry.checksum().equals(row.get("checksum")))
                throw historyMismatch(pluginId);
            resolved.add(
                    new ResolvedMigrationImpl(
                            MigrationVersion.fromVersion(entry.version()),
                            entry.description(),
                            entry.script(),
                            entry.checksum(),
                            null,
                            CoreMigrationType.SQL,
                            "published-history:" + pluginId + "/" + entry.script(),
                            VALIDATION_ONLY));
        }
        if (!resolved.isEmpty() && (initialized || resolved.size() != published.size()))
            throw new IllegalStateException("incompletePublishedPluginHistory:" + pluginId);
        return List.copyOf(resolved);
    }

    private static IllegalStateException invalid(String id) {
        return new IllegalStateException("invalidPluginInitializationCatalog:" + id);
    }

    private static IllegalStateException historyMismatch(String id) {
        return new IllegalStateException("publishedPluginHistoryMismatch:" + id);
    }

    private static final MigrationExecutor VALIDATION_ONLY =
            new MigrationExecutor() {
                @Override
                public List<Results> execute(Context context) throws SQLException {
                    throw new SQLException("Published plugin metadata cannot execute a migration");
                }

                @Override
                public boolean canExecuteInTransaction() {
                    return true;
                }

                @Override
                public boolean shouldExecute() {
                    return true;
                }
            };

    private record PublishedHistoryResolver(List<ResolvedMigration> migrations)
            implements MigrationResolver {
        @Override
        public Collection<ResolvedMigration> resolveMigrations(MigrationResolver.Context context) {
            return migrations;
        }
    }
}
