package com.lumira.platform.plugins;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.executor.Context;
import org.flywaydb.core.api.executor.MigrationExecutor;
import org.flywaydb.core.api.resolver.MigrationResolver;
import org.flywaydb.core.api.resolver.ResolvedMigration;
import org.flywaydb.core.internal.jdbc.Results;
import org.flywaydb.core.internal.resolver.ResolvedMigrationImpl;
import org.springframework.jdbc.core.JdbcTemplate;

/** Applies plugin migrations with checksum-preserving handling of legacy permission scripts. */
final class PluginPermissionMigrationRunner {
    record LegacyPermissionMigration(
            String version, String previousVersion, String script, boolean onlyForFreshInstall) {}

    private static final Map<String, LegacyPermissionMigration> POST_V51_NO_OP_MIGRATIONS =
            Map.of(
                    "feedback",
                    new LegacyPermissionMigration(
                            "2", "1", "V2__independent_page_operations.sql", true),
                    "localization",
                    new LegacyPermissionMigration(
                            "2", "1", "V2__independent_page_operations.sql", true),
                    "sensitive-words",
                    new LegacyPermissionMigration(
                            "2", "1", "V2__independent_page_operations.sql", true),
                    "mfa",
                    new LegacyPermissionMigration(
                            "6", "5", "V6__fine_grained_admin_permissions.sql", false));

    private static final Map<String, String> REQUIRED_FINALIZERS =
            Map.of(
                    "feedback", "V3__finalize_platform_permission_migration.sql",
                    "localization", "V3__finalize_platform_permission_migration.sql",
                    "sensitive-words", "V3__finalize_platform_permission_migration.sql",
                    "mfa", "V7__finalize_legacy_mfa_permissions.sql",
                    "payment-core", "V4__finalize_legacy_payment_permissions.sql",
                    "wechat-pay", "V2__finalize_legacy_payment_permissions.sql",
                    "alipay-pay", "V2__finalize_legacy_payment_permissions.sql");

    private PluginPermissionMigrationRunner() {}

    static Optional<LegacyPermissionMigration> noOpMigrationFor(String pluginId) {
        return Optional.ofNullable(POST_V51_NO_OP_MIGRATIONS.get(pluginId));
    }

    static boolean shouldUseNoOp(
            String pluginId, boolean hostV51, boolean hasAppliedVersionedMigration) {
        LegacyPermissionMigration policy = POST_V51_NO_OP_MIGRATIONS.get(pluginId);
        return hostV51
                && policy != null
                && (!policy.onlyForFreshInstall() || !hasAppliedVersionedMigration);
    }

    static boolean requiresV51(String pluginId, Path migrations) {
        String finalizer = REQUIRED_FINALIZERS.get(pluginId);
        return finalizer != null && Files.isRegularFile(migrations.resolve(finalizer));
    }

    static void migrate(DataSource dataSource, String pluginId, Path migrations) {
        if (PluginInitializationMigrationRunner.supports(migrations)) {
            PluginInitializationMigrationRunner.migrate(dataSource, pluginId, migrations);
            return;
        }
        String historyTable = "plugin_" + pluginId.replace('-', '_') + "_schema_history";
        String location = "filesystem:" + migrations.toAbsolutePath();
        boolean hostV51 =
                Boolean.TRUE.equals(
                        new JdbcTemplate(dataSource)
                                .queryForObject(
                                        "SELECT to_regprocedure("
                                                + "'public.platform_normalize_legacy_permission_projections()') "
                                                + "IS NOT NULL",
                                        Boolean.class));

        if (!hostV51 && requiresV51(pluginId, migrations)) {
            throw new IllegalStateException("pluginPermissionFinalizationRequiresV51:" + pluginId);
        }

        var standard = flyway(dataSource, historyTable, location).load();
        if (!hostV51) {
            standard.migrate();
            return;
        }

        var policy = POST_V51_NO_OP_MIGRATIONS.get(pluginId);
        if (policy == null) {
            standard.migrate();
            return;
        }

        MigrationInfo selected =
                Arrays.stream(standard.info().pending())
                        .filter(
                                migration ->
                                        migration.getVersion() != null
                                                && migration
                                                        .getVersion()
                                                        .equals(
                                                                MigrationVersion.fromVersion(
                                                                        policy.version())))
                        .findFirst()
                        .orElse(null);
        if (selected == null) {
            standard.migrate();
            return;
        }
        String resolvedScript = selected.getScript().replace('\\', '/');
        // In the pinned Flyway 12.4 MigrationInfoImpl, getResolvedType() and
        // getResolvedDescription() are inherited defaults that return null. For a pending
        // migration, the stable getters expose the resolved source values.
        Integer sourceChecksum = selected.getChecksum();
        var sourceType = selected.getType();
        if (!(resolvedScript.equals(policy.script())
                        || resolvedScript.endsWith("/" + policy.script()))
                || sourceType == null
                || !"SQL".equals(sourceType.name())
                || sourceChecksum == null) {
            throw new IllegalStateException(
                    "unexpectedLegacyPluginPermissionMigration:"
                            + pluginId
                            + ":"
                            + selected.getScript());
        }

        boolean hasAppliedVersionedMigration =
                Arrays.stream(standard.info().applied())
                        .anyMatch(
                                migration ->
                                        migration.getVersion() != null
                                                && (migration.getType() == null
                                                        || !"BASELINE"
                                                                .equals(
                                                                        migration
                                                                                .getType()
                                                                                .name())));
        if (!shouldUseNoOp(pluginId, true, hasAppliedVersionedMigration)) {
            standard.migrate();
            return;
        }

        flyway(dataSource, historyTable, location)
                .target(policy.previousVersion())
                .load()
                .migrate();

        var resolvedNoOp = noOpResolution(selected);
        flyway(dataSource, historyTable, location)
                .target(policy.version())
                .validateOnMigrate(false)
                .skipDefaultResolvers(true)
                .resolvers(new SingleMigrationResolver(resolvedNoOp))
                .load()
                .migrate();

        // The regular resolver verifies that Flyway recorded the immutable source migration with
        // its original checksum, then applies the remaining appended migrations normally.
        standard.migrate();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(
            DataSource dataSource, String historyTable, String location) {
        return Flyway.configure()
                .dataSource(dataSource)
                .table(historyTable)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations(location);
    }

    private static ResolvedMigration noOpResolution(MigrationInfo source) {
        MigrationExecutor noOpExecutor =
                new MigrationExecutor() {
                    @Override
                    public List<Results> execute(Context context) throws SQLException {
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
                };
        return new ResolvedMigrationImpl(
                source.getVersion(),
                source.getDescription(),
                source.getScript(),
                source.getChecksum(),
                null,
                source.getType(),
                source.getPhysicalLocation(),
                noOpExecutor);
    }

    private record SingleMigrationResolver(ResolvedMigration migration)
            implements MigrationResolver {
        @Override
        public Collection<ResolvedMigration> resolveMigrations(MigrationResolver.Context context) {
            return List.of(migration);
        }
    }
}
