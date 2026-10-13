package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Runs the published MFA migrations plus V7 against an explicitly disposable, isolated database.
 */
@EnabledIfEnvironmentVariable(
        named = "LUMIRA_PLUGIN_PERMISSION_FINALIZER_TEST_DB_URL",
        matches = ".+")
class MfaPluginPermissionFinalizerDatabaseTest {
    private static final String LEGACY_RESET = "platform:mfa:reset";
    private static final String LEGACY_READ = "platform:mfa:read";
    private static final String LEGACY_MANAGE = "platform:mfa:manage";
    private static final String USERS_UPDATE = "platform:users:update";
    private static final String MFA_RESET = "platform:mfa:management-reset";
    private static final String MFA_ACCESS = "platform:mfa:management-access";
    private static final String MFA_READ = "platform:mfa:management-read";
    private static final String FEEDBACK_UPDATE = "platform:feedback:update";
    private static final String FEEDBACK_ACCESS = "platform:feedback:access";

    private static final UUID DIRECT_RESET_ACTOR = id("20000000-0000-0000-0000-000000000001");
    private static final UUID ROLE_RESET_ACTOR = id("20000000-0000-0000-0000-000000000002");
    private static final UUID CROSS_ROLE_ACTOR = id("20000000-0000-0000-0000-000000000003");
    private static final UUID RESET_ONLY_ACTOR = id("20000000-0000-0000-0000-000000000004");
    private static final UUID READ_MANAGE_ACTOR = id("20000000-0000-0000-0000-000000000005");
    private static final UUID PEER = id("20000000-0000-0000-0000-000000000006");
    private static final UUID FEEDBACK_ACTION_ACTOR = id("20000000-0000-0000-0000-000000000007");
    private static final UUID MFA_BOOTSTRAP_ADMIN = id("20000000-0000-0000-0000-000000000008");

    private static JdbcTemplate jdbc;
    private static Integer feedbackV2Checksum;
    private static Integer mfaV6Checksum;

    @BeforeAll
    static void migrateFreshHostAndPluginDatabase() {
        var dataSource =
                new DriverManagerDataSource(
                        System.getenv("LUMIRA_PLUGIN_PERMISSION_FINALIZER_TEST_DB_URL"),
                        System.getenv("LUMIRA_PLUGIN_PERMISSION_FINALIZER_TEST_DB_USER"),
                        System.getenv("LUMIRA_PLUGIN_PERMISSION_FINALIZER_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        assertTrue(
                database != null && database.startsWith("lumira_plugin_permissions_qa_"),
                "MFA finalizer tests require a dedicated lumira_plugin_permissions_qa_* database.");
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables "
                                + "WHERE table_schema='public' AND table_name='flyway_schema_history'",
                        Integer.class),
                "The MFA finalizer test database must be fresh.");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .load()
                .migrate();

        insertUser(FEEDBACK_ACTION_ACTOR, "plugin-feedback-update-only");
        grantDirect(FEEDBACK_ACTION_ACTOR, FEEDBACK_UPDATE);
        feedbackV2Checksum = resolvedChecksum(dataSource, "feedback", "2");
        PluginPermissionMigrationRunner.migrate(
                dataSource,
                "feedback",
                findRepositoryRoot().resolve("plugins/feedback/migrations"));

        insertUser(MFA_BOOTSTRAP_ADMIN, "admin");
        for (String authority :
                new String[] {
                    "platform:roles:create",
                    "platform:roles:update",
                    "platform:roles:delete",
                    "platform:roles:assign",
                    USERS_UPDATE
                }) {
            grantDirect(MFA_BOOTSTRAP_ADMIN, authority);
        }

        insertUser(DIRECT_RESET_ACTOR, "mfa-plugin-direct-reset");
        insertUser(ROLE_RESET_ACTOR, "mfa-plugin-role-reset");
        insertUser(CROSS_ROLE_ACTOR, "mfa-plugin-cross-role");
        insertUser(RESET_ONLY_ACTOR, "mfa-plugin-reset-only");
        insertUser(READ_MANAGE_ACTOR, "mfa-plugin-read-manage");
        insertUser(PEER, "mfa-plugin-peer");

        UUID directUpdateSelf = insertRole("mfa-plugin-direct-reset-update-self", "SELF");
        UUID allReset = insertRole("mfa-plugin-all-reset", "ALL");
        UUID crossUpdateSelf = insertRole("mfa-plugin-cross-update-self", "SELF");
        grantRole(directUpdateSelf, USERS_UPDATE);
        grantRole(allReset, LEGACY_RESET);
        grantRole(crossUpdateSelf, USERS_UPDATE);
        assign(DIRECT_RESET_ACTOR, directUpdateSelf);
        assign(ROLE_RESET_ACTOR, allReset);
        assign(CROSS_ROLE_ACTOR, allReset);
        assign(CROSS_ROLE_ACTOR, crossUpdateSelf);

        grantDirect(DIRECT_RESET_ACTOR, LEGACY_RESET);
        grantDirect(ROLE_RESET_ACTOR, USERS_UPDATE);
        grantDirect(RESET_ONLY_ACTOR, LEGACY_RESET);
        grantDirect(READ_MANAGE_ACTOR, LEGACY_READ);
        grantDirect(READ_MANAGE_ACTOR, LEGACY_MANAGE);
        grantDirect(READ_MANAGE_ACTOR, USERS_UPDATE);

        Path migrations = findRepositoryRoot().resolve("plugins/mfa/migrations");
        mfaV6Checksum = resolvedChecksum(dataSource, "mfa", "6");
        PluginPermissionMigrationRunner.migrate(dataSource, "mfa", migrations);
    }

    @Test
    void keepsLegacyResetGlobalPrerequisiteAndTargetScopeIndependentAcrossSources() {
        assertTrue(hasEffective(DIRECT_RESET_ACTOR, MFA_RESET));
        assertFalse(hasGlobal(DIRECT_RESET_ACTOR, MFA_RESET));
        assertTrue(canTarget(DIRECT_RESET_ACTOR, DIRECT_RESET_ACTOR));
        assertFalse(canTarget(DIRECT_RESET_ACTOR, PEER));

        assertTrue(hasEffective(ROLE_RESET_ACTOR, MFA_RESET));
        assertTrue(hasGlobal(ROLE_RESET_ACTOR, MFA_RESET));
        assertTrue(canTarget(ROLE_RESET_ACTOR, PEER));

        assertTrue(hasEffective(CROSS_ROLE_ACTOR, MFA_RESET));
        assertFalse(hasGlobal(CROSS_ROLE_ACTOR, MFA_RESET));
        assertTrue(canTarget(CROSS_ROLE_ACTOR, CROSS_ROLE_ACTOR));
        assertFalse(canTarget(CROSS_ROLE_ACTOR, PEER));

        assertFalse(hasEffective(RESET_ONLY_ACTOR, MFA_RESET));
        assertFalse(hasGlobal(RESET_ONLY_ACTOR, MFA_RESET));
        assertTrue(hasEffective(READ_MANAGE_ACTOR, MFA_ACCESS));
        assertTrue(hasEffective(READ_MANAGE_ACTOR, MFA_READ));
        assertFalse(hasEffective(READ_MANAGE_ACTOR, MFA_RESET));
        assertFalse(hasGlobal(READ_MANAGE_ACTOR, MFA_RESET));

        assertEquals(0, remainingLegacyOrOpaqueGrantCount());
    }

    @Test
    void postV51FreshInstallDoesNotLinkPageAndActionOrReplayBootstrapSeeds() {
        assertTrue(hasEffective(FEEDBACK_ACTION_ACTOR, FEEDBACK_UPDATE));
        assertFalse(hasEffective(FEEDBACK_ACTION_ACTOR, FEEDBACK_ACCESS));
        assertFalse(hasEffective(FEEDBACK_ACTION_ACTOR, "platform:feedback:read-all"));

        assertFalse(hasEffective(MFA_BOOTSTRAP_ADMIN, MFA_ACCESS));
        assertFalse(hasEffective(MFA_BOOTSTRAP_ADMIN, MFA_READ));
        assertFalse(hasEffective(MFA_BOOTSTRAP_ADMIN, MFA_RESET));

        assertEquals(
                feedbackV2Checksum,
                migrationChecksum("plugin_feedback_schema_history", "2"),
                "Skipped feedback V2 must retain the immutable source checksum in history.");
        assertEquals(
                mfaV6Checksum,
                migrationChecksum("plugin_mfa_schema_history", "6"),
                "Skipped MFA V6 must retain the immutable source checksum in history.");

        Flyway.configure()
                .dataSource(jdbc.getDataSource())
                .table("plugin_feedback_schema_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations(
                        "filesystem:"
                                + findRepositoryRoot()
                                        .resolve("plugins/feedback/migrations")
                                        .toAbsolutePath())
                .load()
                .validate();
    }

    private static Integer resolvedChecksum(
            DataSource dataSource, String pluginId, String version) {
        String location =
                "filesystem:"
                        + findRepositoryRoot()
                                .resolve("plugins")
                                .resolve(pluginId)
                                .resolve("migrations")
                                .toAbsolutePath();
        return Arrays.stream(
                        Flyway.configure()
                                .dataSource(dataSource)
                                .table("plugin_" + pluginId.replace('-', '_') + "_schema_history")
                                .baselineOnMigrate(true)
                                .baselineVersion("0")
                                .locations(location)
                                .load()
                                .info()
                                .pending())
                .filter(
                        migration ->
                                migration.getVersion() != null
                                        && migration
                                                .getVersion()
                                                .equals(MigrationVersion.fromVersion(version)))
                .map(migration -> migration.getChecksum())
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Missing pending migration " + pluginId + " V" + version));
    }

    private static Integer migrationChecksum(String historyTable, String version) {
        return jdbc.queryForObject(
                "SELECT checksum FROM " + historyTable + " WHERE version=?",
                Integer.class,
                version);
    }

    private static int remainingLegacyOrOpaqueGrantCount() {
        return jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM platform_authority "
                        + "WHERE authority IN (?,?,?) OR authority LIKE 'platform:__v50:%') "
                        + "+ (SELECT count(*) FROM platform_role_authority "
                        + "WHERE authority IN (?,?,?) OR authority LIKE 'platform:__v50:%')",
                Integer.class,
                LEGACY_READ,
                LEGACY_MANAGE,
                LEGACY_RESET,
                LEGACY_READ,
                LEGACY_MANAGE,
                LEGACY_RESET);
    }

    private static boolean hasEffective(UUID userId, String authority) {
        return jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform_effective_authority "
                        + "WHERE user_id=? AND authority=?)",
                Boolean.class,
                userId,
                authority);
    }

    private static boolean hasGlobal(UUID userId, String authority) {
        return jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform_global_authority "
                        + "WHERE user_id=? AND authority=?)",
                Boolean.class,
                userId,
                authority);
    }

    private static boolean canTarget(UUID actor, UUID target) {
        return jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform_visible_users(?,?) WHERE user_id=?)",
                Boolean.class,
                username(actor),
                MFA_RESET,
                target);
    }

    private static Path findRepositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isDirectory(directory.resolve("plugins/mfa/migrations"))) return directory;
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "MFA plugin migrations were not found from the test working directory.");
    }

    private static void insertUser(UUID id, String username) {
        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,password_hash) VALUES (?,?,?,'unused')",
                id,
                username,
                username);
    }

    private static UUID insertRole(String code, String scope) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_role(id,code,name,data_scope) VALUES (?,?,?,?)",
                id,
                code,
                code,
                scope);
        return id;
    }

    private static void grantRole(UUID roleId, String authority) {
        jdbc.update(
                "INSERT INTO platform_role_authority(role_id,authority) VALUES (?,?)",
                roleId,
                authority);
    }

    private static void grantDirect(UUID userId, String authority) {
        jdbc.update(
                "INSERT INTO platform_authority(user_id,authority) VALUES (?,?)",
                userId,
                authority);
    }

    private static void assign(UUID userId, UUID roleId) {
        jdbc.update("INSERT INTO platform_user_role(user_id,role_id) VALUES (?,?)", userId, roleId);
    }

    private static String username(UUID userId) {
        return jdbc.queryForObject(
                "SELECT username FROM platform_user WHERE id=?", String.class, userId);
    }

    private static UUID id(String value) {
        return UUID.fromString(value);
    }
}
