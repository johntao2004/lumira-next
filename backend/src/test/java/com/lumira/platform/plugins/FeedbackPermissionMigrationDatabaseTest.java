package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Exercises feedback plugin permission migrations on the current host baseline. */
@EnabledIfEnvironmentVariable(
        named = "LUMIRA_FEEDBACK_PERMISSION_MIGRATION_TEST_DB_URL",
        matches = ".+")
class FeedbackPermissionMigrationDatabaseTest {
    private static final String ACCESS = "platform:feedback:access";
    private static final String READ = "platform:feedback:read";
    private static final String READ_ALL = "platform:feedback:read-all";
    private static final String UPDATE = "platform:feedback:update";
    private static final String REPLY = "platform:feedback:reply";
    private static final String UNKNOWN = "platform:feedback:future-permission";

    private static final UUID BROAD_READ_USER = UUID.randomUUID();
    private static final UUID ORGANIZATION_READ_USER = UUID.randomUUID();
    private static final UUID DIRECT_READ_USER = UUID.randomUUID();
    private static final UUID SELF_READ_USER = UUID.randomUUID();
    private static final UUID GLOBAL_ACTION_USER = UUID.randomUUID();
    private static final UUID DIRECT_ACTION_USER = UUID.randomUUID();
    private static final UUID SCOPED_ACTION_USER = UUID.randomUUID();
    private static final UUID DISABLED_READ_USER = UUID.randomUUID();
    private static final UUID DISABLED_ACTION_USER = UUID.randomUUID();
    private static final UUID ACTION_ONLY_USER = UUID.randomUUID();
    private static final UUID CROSS_ROLE_USER = UUID.randomUUID();
    private static final UUID PEER_USER = UUID.randomUUID();
    private static final UUID OTHER_USER = UUID.randomUUID();

    private static UUID broadReadRole;
    private static UUID organizationReadRole;
    private static UUID selfReadRole;
    private static UUID globalActionRole;
    private static UUID scopedActionRole;
    private static UUID disabledReadRole;
    private static UUID disabledActionRole;
    private static UUID crossRoleAccessRole;
    private static UUID feedbackOrganization;
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;
    private static Path feedbackMigrations;

    @BeforeAll
    static void migrateHostAndSeedLegacyPluginState() {
        dataSource =
                new DriverManagerDataSource(
                        System.getenv("LUMIRA_FEEDBACK_PERMISSION_MIGRATION_TEST_DB_URL"),
                        System.getenv("LUMIRA_FEEDBACK_PERMISSION_MIGRATION_TEST_DB_USER"),
                        System.getenv("LUMIRA_FEEDBACK_PERMISSION_MIGRATION_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        assertTrue(
                database != null && database.startsWith("lumira_feedback_permission_qa_"),
                "Feedback permission migration tests require a dedicated empty QA database.");
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables "
                                + "WHERE table_schema='public' AND table_name='flyway_schema_history'",
                        Integer.class),
                "The feedback permission migration database must be fresh.");

        Flyway host =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .schemas("public")
                        .defaultSchema("public")
                        .load();
        host.migrate();
        assertEquals("52", host.info().current().getVersion().getVersion());

        insertUser(BROAD_READ_USER, "feedback-migration-broad-read");
        insertUser(ORGANIZATION_READ_USER, "feedback-migration-org-read");
        insertUser(DIRECT_READ_USER, "feedback-migration-direct-read");
        insertUser(SELF_READ_USER, "feedback-migration-self-read");
        insertUser(GLOBAL_ACTION_USER, "feedback-migration-global-action");
        insertUser(DIRECT_ACTION_USER, "feedback-migration-direct-action");
        insertUser(SCOPED_ACTION_USER, "feedback-migration-scoped-action");
        insertUser(DISABLED_READ_USER, "feedback-migration-disabled-read");
        insertUser(DISABLED_ACTION_USER, "feedback-migration-disabled-action");
        insertUser(ACTION_ONLY_USER, "feedback-migration-action-only");
        insertUser(CROSS_ROLE_USER, "feedback-migration-cross-role");
        insertUser(PEER_USER, "feedback-migration-peer");
        insertUser(OTHER_USER, "feedback-migration-other");

        broadReadRole = insertRole("feedback-migration-broad-read", "ALL", true);
        organizationReadRole = insertRole("feedback-migration-org-read", "ORGANIZATION", true);
        selfReadRole = insertRole("feedback-migration-self-read", "SELF", true);
        globalActionRole = insertRole("feedback-migration-global-action", "ALL", true);
        scopedActionRole = insertRole("feedback-migration-scoped-action", "ORGANIZATION", true);
        disabledReadRole = insertRole("feedback-migration-disabled-read", "ALL", false);
        disabledActionRole = insertRole("feedback-migration-disabled-action", "ALL", false);

        grantRole(broadReadRole, READ);
        grantRole(broadReadRole, ACCESS);
        assign(BROAD_READ_USER, broadReadRole);

        grantRole(organizationReadRole, READ);
        grantRole(organizationReadRole, ACCESS);
        assign(ORGANIZATION_READ_USER, organizationReadRole);

        grantDirect(DIRECT_READ_USER, READ);
        grantDirect(DIRECT_READ_USER, ACCESS);

        grantRole(selfReadRole, READ);
        grantRole(selfReadRole, ACCESS);
        assign(SELF_READ_USER, selfReadRole);

        grantRole(globalActionRole, READ_ALL);
        grantRole(globalActionRole, UPDATE);
        grantRole(globalActionRole, REPLY);
        grantRole(globalActionRole, ACCESS);
        grantRole(globalActionRole, UNKNOWN);
        assign(GLOBAL_ACTION_USER, globalActionRole);

        grantDirect(DIRECT_ACTION_USER, READ_ALL);
        grantDirect(DIRECT_ACTION_USER, UPDATE);
        grantDirect(DIRECT_ACTION_USER, REPLY);
        grantDirect(DIRECT_ACTION_USER, UNKNOWN);

        grantRole(scopedActionRole, READ_ALL);
        grantRole(scopedActionRole, UPDATE);
        grantRole(scopedActionRole, REPLY);
        grantRole(scopedActionRole, ACCESS);
        grantRole(scopedActionRole, UNKNOWN);
        assign(SCOPED_ACTION_USER, scopedActionRole);

        grantRole(disabledReadRole, READ);
        grantRole(disabledReadRole, ACCESS);
        assign(DISABLED_READ_USER, disabledReadRole);

        grantRole(disabledActionRole, READ_ALL);
        grantRole(disabledActionRole, UPDATE);
        grantRole(disabledActionRole, REPLY);
        grantRole(disabledActionRole, ACCESS);
        assign(DISABLED_ACTION_USER, disabledActionRole);

        feedbackOrganization = insertOrganization("feedback-migration-peer-org");
        assignOrganization(ORGANIZATION_READ_USER, feedbackOrganization);
        assignOrganization(PEER_USER, feedbackOrganization);

        Path repositoryRoot = findRepositoryRoot();
        feedbackMigrations = repositoryRoot.resolve("plugins/feedback/migrations");

        // Exercise the plugin migration against current host tables and permissions.
        grantDirect(ACTION_ONLY_USER, UPDATE);
        crossRoleAccessRole =
                insertRole("feedback-migration-cross-role-access", "ORGANIZATION", true);
        grantRole(crossRoleAccessRole, ACCESS);
        assign(CROSS_ROLE_USER, crossRoleAccessRole);
        assignOrganization(CROSS_ROLE_USER, feedbackOrganization);
        grantDirect(CROSS_ROLE_USER, UPDATE);

        // Exercise the final initialization handling of historical grants that still exist in
        // plugin data.
        grantRole(scopedActionRole, READ_ALL);
        grantRole(scopedActionRole, UPDATE);
        grantRole(scopedActionRole, REPLY);
    }

    @Test
    void keepsLegacyReadsOwnerOnlyAndOnlyCarriesForwardGlobalActionRead() throws Exception {
        assertTrue(canTarget(BROAD_READ_USER, PEER_USER));
        assertTrue(canTarget(ORGANIZATION_READ_USER, PEER_USER));
        assertTrue(canTarget(DIRECT_READ_USER, PEER_USER));

        long broadRoleVersion = roleVersion(broadReadRole);
        long organizationRoleVersion = roleVersion(organizationReadRole);
        long globalActionRoleVersion = roleVersion(globalActionRole);
        long scopedActionRoleVersion = roleVersion(scopedActionRole);
        long directReadUserVersion = userVersion(DIRECT_READ_USER);
        long actionOnlyUserVersion = userVersion(ACTION_ONLY_USER);

        PluginPermissionMigrationRunner.migrate(dataSource, "feedback", feedbackMigrations);

        assertEquals(
                "4",
                jdbc.queryForObject(
                        "SELECT version FROM plugin_feedback_schema_history "
                                + "ORDER BY installed_rank DESC LIMIT 1",
                        String.class));

        assertFalse(canTarget(BROAD_READ_USER, PEER_USER));
        assertFalse(canTarget(ORGANIZATION_READ_USER, PEER_USER));
        assertFalse(canTarget(DIRECT_READ_USER, PEER_USER));
        assertTrue(canTarget(BROAD_READ_USER, BROAD_READ_USER));
        assertTrue(canTarget(ORGANIZATION_READ_USER, ORGANIZATION_READ_USER));
        assertTrue(canTarget(DIRECT_READ_USER, DIRECT_READ_USER));
        assertFalse(hasGlobal(BROAD_READ_USER, READ));
        assertFalse(hasGlobal(ORGANIZATION_READ_USER, READ));
        assertFalse(hasGlobal(DIRECT_READ_USER, READ));

        assertTrue(hasEffective(BROAD_READ_USER, ACCESS));
        assertTrue(hasEffective(ORGANIZATION_READ_USER, ACCESS));
        assertTrue(hasEffective(DIRECT_READ_USER, ACCESS));
        UUID broadMigrationRole = migrationReadRole(BROAD_READ_USER);
        UUID organizationMigrationRole = migrationReadRole(ORGANIZATION_READ_USER);
        UUID directMigrationRole = migrationReadRole(DIRECT_READ_USER);
        assertMigrationRoleContainsOnlyRead(broadMigrationRole);
        assertMigrationRoleContainsOnlyRead(organizationMigrationRole);
        assertMigrationRoleContainsOnlyRead(directMigrationRole);
        assertFalse(roleHas(broadMigrationRole, ACCESS));
        assertFalse(roleHas(organizationMigrationRole, ACCESS));
        assertFalse(roleHas(directMigrationRole, ACCESS));
        assertEquals("SELF", roleScope(broadMigrationRole));
        assertEquals("SELF", roleScope(organizationMigrationRole));
        assertEquals("SELF", roleScope(directMigrationRole));

        assertTrue(roleHas(selfReadRole, READ));
        assertTrue(hasEffective(SELF_READ_USER, READ));
        assertTrue(canTarget(SELF_READ_USER, SELF_READ_USER));
        assertFalse(canTarget(SELF_READ_USER, PEER_USER));
        assertEquals(0, migrationRoleCount(SELF_READ_USER));

        assertTrue(roleHas(globalActionRole, READ));
        assertTrue(roleHas(globalActionRole, UPDATE));
        assertTrue(roleHas(globalActionRole, REPLY));
        assertFalse(roleHas(globalActionRole, READ_ALL));
        assertTrue(hasGlobal(GLOBAL_ACTION_USER, READ));
        assertTrue(canTarget(GLOBAL_ACTION_USER, PEER_USER));
        assertTrue(hasGlobal(DIRECT_ACTION_USER, READ));
        assertTrue(hasEffective(DIRECT_ACTION_USER, UPDATE));
        assertTrue(hasEffective(DIRECT_ACTION_USER, REPLY));
        assertFalse(hasEffective(DIRECT_ACTION_USER, READ_ALL));
        assertTrue(canTarget(DIRECT_ACTION_USER, PEER_USER));

        assertFalse(roleHas(scopedActionRole, READ_ALL));
        assertFalse(roleHas(scopedActionRole, UPDATE));
        assertFalse(roleHas(scopedActionRole, REPLY));
        assertFalse(hasEffective(SCOPED_ACTION_USER, READ));
        assertFalse(hasEffective(SCOPED_ACTION_USER, UPDATE));
        assertFalse(hasEffective(SCOPED_ACTION_USER, REPLY));
        assertTrue(hasEffective(SCOPED_ACTION_USER, ACCESS));
        assertTrue(roleHas(scopedActionRole, UNKNOWN));
        assertTrue(hasEffective(DIRECT_ACTION_USER, UNKNOWN));

        assertFalse(hasEffective(ACTION_ONLY_USER, ACCESS));
        assertTrue(hasEffective(ACTION_ONLY_USER, READ));
        assertFalse(hasPageAndRead(ACTION_ONLY_USER));
        assertEquals(0, migrationRoleCount(ACTION_ONLY_USER));
        assertTrue(hasPageAndRead(CROSS_ROLE_USER));
        assertTrue(hasEffective(CROSS_ROLE_USER, READ));
        assertTrue(hasEffective(CROSS_ROLE_USER, ACCESS));

        assertFalse(hasEffective(DISABLED_READ_USER, READ));
        assertFalse(hasEffective(DISABLED_READ_USER, ACCESS));
        assertEquals(0, migrationRoleCount(DISABLED_READ_USER));
        assertFalse(roleEnabled(disabledReadRole));
        assertTrue(roleHas(disabledActionRole, READ));
        assertFalse(hasEffective(DISABLED_ACTION_USER, READ));
        assertFalse(hasEffective(DISABLED_ACTION_USER, UPDATE));
        assertFalse(hasEffective(DISABLED_ACTION_USER, ACCESS));
        assertFalse(roleEnabled(disabledActionRole));

        assertEquals(0, rawGrantCount(READ_ALL));
        assertTrue(isRetired(READ_ALL));
        assertTrue(roleVersion(broadReadRole) > broadRoleVersion);
        assertTrue(roleVersion(organizationReadRole) > organizationRoleVersion);
        assertTrue(roleVersion(globalActionRole) > globalActionRoleVersion);
        assertTrue(roleVersion(scopedActionRole) > scopedActionRoleVersion);
        assertTrue(userVersion(DIRECT_READ_USER) > directReadUserVersion);
        assertTrue(userVersion(ACTION_ONLY_USER) > actionOnlyUserVersion);

        // A later assignment of the old broad source role does not inherit READ. The generated
        // migration role is an ordinary role and must be assigned explicitly to a new account.
        UUID futureUser = UUID.randomUUID();
        insertUser(futureUser, "feedback-migration-future-reader");
        assign(futureUser, organizationReadRole);
        assertTrue(hasEffective(futureUser, ACCESS));
        assertFalse(hasEffective(futureUser, READ));
        assertFalse(hasPageAndRead(futureUser));

        UUID explicitlyAssignedReader = UUID.randomUUID();
        insertUser(explicitlyAssignedReader, "feedback-migration-explicit-reader");
        assign(explicitlyAssignedReader, broadMigrationRole);
        assertTrue(hasEffective(explicitlyAssignedReader, READ));
        assertFalse(hasEffective(explicitlyAssignedReader, ACCESS));
        assertFalse(hasPageAndRead(explicitlyAssignedReader));

        // The migration role does not keep page access alive if its original ACCESS source is
        // later removed.
        jdbc.update(
                "DELETE FROM platform_role_authority WHERE role_id=? AND authority=?",
                broadReadRole,
                ACCESS);
        assertTrue(hasEffective(BROAD_READ_USER, READ));
        assertFalse(hasEffective(BROAD_READ_USER, ACCESS));
        assertFalse(hasPageAndRead(BROAD_READ_USER));

        assertTrue(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM platform_permission_migration_archive "
                                + "WHERE source_authority=? AND principal_kind='ROLE' "
                                + "AND principal_id=?)",
                        Boolean.class,
                        READ,
                        broadReadRole));
        assertTrue(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM platform_permission_migration_archive "
                                + "WHERE source_authority=? AND logical_authority=? "
                                + "AND normalized_authority=? AND principal_kind='ROLE' "
                                + "AND principal_id=?)",
                        Boolean.class,
                        UPDATE,
                        READ,
                        READ,
                        globalActionRole));
        assertTrue(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM platform_permission_migration_archive "
                                + "WHERE source_authority=? AND logical_authority=? "
                                + "AND normalized_authority IS NULL AND principal_kind='ROLE' "
                                + "AND principal_id=?)",
                        Boolean.class,
                        UPDATE,
                        UPDATE,
                        scopedActionRole));

        long roleCountBeforeReplay = migrationRoleCount();
        long archiveCountBeforeReplay = archiveCount();
        long broadVersionBeforeReplay = roleVersion(broadReadRole);
        long readUserVersionBeforeReplay = userVersion(DIRECT_READ_USER);
        runSqlMigrationAgain(feedbackMigrations.resolve("V4__plugin_initialization.sql"));
        assertEquals(roleCountBeforeReplay, migrationRoleCount());
        assertEquals(archiveCountBeforeReplay, archiveCount());
        assertEquals(broadVersionBeforeReplay, roleVersion(broadReadRole));
        assertEquals(readUserVersionBeforeReplay, userVersion(DIRECT_READ_USER));
    }

    private static UUID insertRole(String code, String scope, boolean enabled) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_role(id,code,name,data_scope,enabled) VALUES (?,?,?,?,?)",
                id,
                code,
                code,
                scope,
                enabled);
        return id;
    }

    private static void insertUser(UUID id, String username) {
        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,password_hash) "
                        + "VALUES (?,?,?,'unused')",
                id,
                username,
                username);
    }

    private static UUID insertOrganization(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_organization(id,parent_id,code,name) VALUES (?,NULL,?,?)",
                id,
                code,
                code);
        return id;
    }

    private static void grantRole(UUID roleId, String authority) {
        jdbc.update(
                "INSERT INTO platform_role_authority(role_id,authority) VALUES (?,?) ON CONFLICT DO NOTHING",
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

    private static void assignOrganization(UUID userId, UUID organizationId) {
        jdbc.update(
                "INSERT INTO platform_user_organization(user_id,organization_id) VALUES (?,?)",
                userId,
                organizationId);
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
                READ,
                target);
    }

    private static boolean hasPageAndRead(UUID userId) {
        return hasEffective(userId, ACCESS) && hasEffective(userId, READ);
    }

    private static String username(UUID userId) {
        return jdbc.queryForObject(
                "SELECT username FROM platform_user WHERE id=?", String.class, userId);
    }

    private static UUID migrationReadRole(UUID userId) {
        return jdbc.queryForObject(
                "SELECT role.id FROM platform_user_role assigned "
                        + "JOIN platform_role role ON role.id=assigned.role_id "
                        + "WHERE assigned.user_id=? "
                        + "AND role.code='migrated-feedback-read-' || replace(?::text,'-','')",
                UUID.class,
                userId,
                userId);
    }

    private static long migrationRoleCount(UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM platform_user_role assigned "
                        + "JOIN platform_role role ON role.id=assigned.role_id "
                        + "WHERE assigned.user_id=? AND role.code LIKE 'migrated-feedback-read-%'",
                Long.class, userId);
    }

    private static long migrationRoleCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM platform_role "
                        + "WHERE code LIKE 'migrated-feedback-read-%'",
                Long.class);
    }

    private static void assertMigrationRoleContainsOnlyRead(UUID roleId) {
        assertTrue(roleHas(roleId, READ));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_role_authority "
                                + "WHERE role_id=? AND authority<>?",
                        Integer.class,
                        roleId,
                        READ));
    }

    private static boolean roleHas(UUID roleId, String authority) {
        return jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform_role_authority "
                        + "WHERE role_id=? AND authority=?)",
                Boolean.class,
                roleId,
                authority);
    }

    private static String roleScope(UUID roleId) {
        return jdbc.queryForObject(
                "SELECT data_scope FROM platform_role WHERE id=?", String.class, roleId);
    }

    private static boolean roleEnabled(UUID roleId) {
        return jdbc.queryForObject(
                "SELECT enabled FROM platform_role WHERE id=?", Boolean.class, roleId);
    }

    private static int rawGrantCount(String authority) {
        return jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM platform_role_authority WHERE authority=?) + "
                        + "(SELECT count(*) FROM platform_authority WHERE authority=?)",
                Integer.class,
                authority,
                authority);
    }

    private static boolean isRetired(String authority) {
        return jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform_permission_retired_authority "
                        + "WHERE authority=?)",
                Boolean.class,
                authority);
    }

    private static long roleVersion(UUID roleId) {
        return jdbc.queryForObject(
                "SELECT version FROM platform_role WHERE id=?", Long.class, roleId);
    }

    private static long userVersion(UUID userId) {
        return jdbc.queryForObject(
                "SELECT version FROM platform_user WHERE id=?", Long.class, userId);
    }

    private static long archiveCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM platform_permission_migration_archive", Long.class);
    }

    private static void runSqlMigrationAgain(Path migration) throws Exception {
        assertTrue(Files.isRegularFile(migration));
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(Files.readString(migration));
            }
            connection.commit();
        }
    }

    private static Path findRepositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isDirectory(directory.resolve("plugins/feedback/migrations"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "Feedback plugin migrations were not found from the test working directory.");
    }
}
