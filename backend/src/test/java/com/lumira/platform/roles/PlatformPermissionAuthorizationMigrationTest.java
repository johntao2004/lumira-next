package com.lumira.platform.roles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.GlobalAuthenticationPolicyPort;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.identity.AdministrationGuard;
import com.lumira.platform.identity.api.AuthenticationMethodAvailabilityPort;
import com.lumira.platform.identity.api.AuthenticationReachabilityPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

/** Runs V50 and V51 against a seeded V49 permission state in an explicitly disposable database. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_PERMISSION_MIGRATION_TEST_DB_URL", matches = ".+")
class PlatformPermissionAuthorizationMigrationTest {
    private static final UUID SCOPED_MFA_ACTOR = id("10000000-0000-0000-0000-000000000001");
    private static final UUID MIXED_MFA_ACTOR = id("10000000-0000-0000-0000-000000000002");
    private static final UUID INVALID_MFA_ACTOR = id("10000000-0000-0000-0000-000000000003");
    private static final UUID POLICY_ACTOR = id("10000000-0000-0000-0000-000000000004");
    private static final UUID REGISTRATION_ACTOR = id("10000000-0000-0000-0000-000000000005");
    private static final UUID WRITE_ONLY_ACTOR = id("10000000-0000-0000-0000-000000000006");
    private static final UUID EXPLICIT_RESET_ACTOR = id("10000000-0000-0000-0000-000000000007");
    private static final UUID LEGACY_RESET_ACTOR = id("10000000-0000-0000-0000-000000000008");
    private static final UUID SCOPED_AI_ACTOR = id("10000000-0000-0000-0000-000000000009");
    private static final UUID GLOBAL_AI_ACTOR = id("10000000-0000-0000-0000-000000000010");
    private static final UUID GLOBAL_AI_NO_PAGE_ACTOR = id("10000000-0000-0000-0000-000000000011");
    private static final UUID DICTIONARY_ONLY_ACTOR = id("10000000-0000-0000-0000-000000000012");
    private static final UUID FEEDBACK_WITHOUT_READ_ALL_ACTOR =
            id("10000000-0000-0000-0000-000000000013");
    private static final UUID FEEDBACK_MIXED_SCOPE_ACTOR =
            id("10000000-0000-0000-0000-000000000014");
    private static final UUID FEEDBACK_LEGACY_FULL_ACTOR =
            id("10000000-0000-0000-0000-000000000015");
    private static final UUID FEEDBACK_NEW_ACTIONS_ACTOR =
            id("10000000-0000-0000-0000-000000000016");
    private static final UUID SCOPED_MFA_PEER = id("10000000-0000-0000-0000-000000000017");
    private static final UUID DISABLED_POLICY_ACTOR = id("10000000-0000-0000-0000-000000000018");
    private static final UUID ORGANIZATION_MFA_ACTOR = id("10000000-0000-0000-0000-000000000019");
    private static final UUID SUBTREE_MFA_ACTOR = id("10000000-0000-0000-0000-000000000021");
    private static final UUID ORGANIZATION_PEER = id("10000000-0000-0000-0000-000000000022");
    private static final UUID ORGANIZATION_CHILD_USER = id("10000000-0000-0000-0000-000000000023");
    private static final UUID UNRELATED_ORGANIZATION_USER =
            id("10000000-0000-0000-0000-000000000024");
    private static final UUID OTHER_USER = id("10000000-0000-0000-0000-000000000020");
    private static final String MFA_RESET = "platform:mfa:management-reset";
    private static final String ROLE_POLICY_READ = "platform:roles:read-authentication-policy";
    private static final String REGISTRATION_ASSIGN = "platform:registration:assign-default-role";
    private static final String AI_CONSOLE_ACCESS = "platform:ai-console:access";
    private static final String AI_CONSOLE_CREATE_USER = "platform:ai-console:create-user";
    private static final String AI_CONSOLE_UPDATE_USER = "platform:ai-console:update-user";
    private static final String AI_CONSOLE_DELETE_USER = "platform:ai-console:delete-user";
    private static final String FEEDBACK_ACCESS = "platform:feedback:access";
    private static final String FEEDBACK_READ = "platform:feedback:read";
    private static final String FEEDBACK_READ_ALL = "platform:feedback:read-all";
    private static final String FEEDBACK_UPDATE = "platform:feedback:update";
    private static final String FEEDBACK_REPLY = "platform:feedback:reply";
    private static final List<AuthorizationSnapshot> V50_SNAPSHOTS = new ArrayList<>();
    private static boolean RETIRED_USERS_READ_EFFECTIVE;
    private static boolean RETIRED_USERS_READ_GLOBAL;
    private static JdbcTemplate jdbc;
    private static Flyway flyway;
    private static AdministrationGuard administrationGuard;

    @BeforeAll
    static void migrateFreshDatabaseAndSeedV49State() {
        var dataSource =
                new DriverManagerDataSource(
                        System.getenv("LUMIRA_PERMISSION_MIGRATION_TEST_DB_URL"),
                        System.getenv("LUMIRA_PERMISSION_MIGRATION_TEST_DB_USER"),
                        System.getenv("LUMIRA_PERMISSION_MIGRATION_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        administrationGuard = new AdministrationGuard(JdbcClient.create(dataSource));
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        assertTrue(
                database != null && database.startsWith("lumira_permission_qa_"),
                "Permission migration tests require a dedicated lumira_permission_qa_* database.");
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables "
                                + "WHERE table_schema='public' AND table_name='flyway_schema_history'",
                        Integer.class),
                "The permission migration database must be fresh.");

        flyway =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .schemas("public")
                        .defaultSchema("public")
                        .target("49")
                        .load();
        flyway.migrate();

        insertUser(SCOPED_MFA_ACTOR, "permission-mfa-scoped");
        insertUser(MIXED_MFA_ACTOR, "permission-mfa-mixed");
        insertUser(INVALID_MFA_ACTOR, "permission-mfa-invalid");
        insertUser(POLICY_ACTOR, "permission-policy-mixed");
        insertUser(REGISTRATION_ACTOR, "permission-registration-mixed");
        insertUser(WRITE_ONLY_ACTOR, "permission-write-only");
        insertUser(LEGACY_RESET_ACTOR, "permission-legacy-reset");
        insertUser(EXPLICIT_RESET_ACTOR, "permission-explicit-reset");
        insertUser(SCOPED_AI_ACTOR, "permission-ai-scoped");
        insertUser(GLOBAL_AI_ACTOR, "permission-ai-global");
        insertUser(GLOBAL_AI_NO_PAGE_ACTOR, "permission-ai-no-page");
        insertUser(DICTIONARY_ONLY_ACTOR, "permission-dictionary-only");
        insertUser(FEEDBACK_WITHOUT_READ_ALL_ACTOR, "permission-feedback-no-read-all");
        insertUser(FEEDBACK_MIXED_SCOPE_ACTOR, "permission-feedback-mixed-scope");
        insertUser(FEEDBACK_LEGACY_FULL_ACTOR, "permission-feedback-legacy-full");
        insertUser(FEEDBACK_NEW_ACTIONS_ACTOR, "permission-feedback-new-actions");
        insertUser(SCOPED_MFA_PEER, "permission-mfa-scoped-peer");
        insertUser(DISABLED_POLICY_ACTOR, "permission-policy-disabled-user");
        insertUser(ORGANIZATION_MFA_ACTOR, "permission-mfa-organization");
        insertUser(SUBTREE_MFA_ACTOR, "permission-mfa-subtree");
        insertUser(ORGANIZATION_PEER, "permission-organization-peer");
        insertUser(ORGANIZATION_CHILD_USER, "permission-organization-child");
        insertUser(UNRELATED_ORGANIZATION_USER, "permission-unrelated-organization");
        insertUser(OTHER_USER, "permission-other-user");

        UUID resetAll = insertRole("permission-reset-all", "ALL");
        UUID updateSelf = insertRole("permission-update-self", "SELF");
        UUID updateOrganization = insertRole("permission-update-organization", "ORGANIZATION");
        UUID updateSubtree = insertRole("permission-update-subtree", "SUBTREE");
        UUID resetSelf = insertRole("permission-reset-self", "SELF");
        UUID updateAll = insertRole("permission-update-all", "ALL");
        UUID roleRead = insertRole("permission-role-read", "ALL");
        UUID disabledRoleRead = insertRole("permission-role-read-disabled", "ALL");
        UUID roleAssign = insertRole("permission-role-assign", "ALL");
        UUID writeOnly = insertRole("permission-write-only", "ALL");
        UUID legacyReset = insertRole("permission-legacy-reset", "SELF");
        UUID aiUpdateSelf = insertRole("permission-ai-update-self", "SELF");
        UUID aiToolsAll = insertRole("permission-ai-tools-all", "ALL");
        UUID dictionaryReadAll = insertRole("permission-dictionary-read", "ALL");
        UUID feedbackReadSelf = insertRole("permission-feedback-read-self", "SELF");
        UUID feedbackReadAll = insertRole("permission-feedback-read-all", "ALL");
        UUID feedbackUpdateSelf = insertRole("permission-feedback-update-self", "SELF");
        UUID feedbackUpdateAll = insertRole("permission-feedback-update-all", "ALL");
        UUID feedbackReplyAll = insertRole("permission-feedback-reply-all", "ALL");

        grantRole(resetAll, "platform:mfa:reset");
        grantRole(updateSelf, "platform:users:update");
        grantRole(updateOrganization, "platform:users:update");
        grantRole(updateSubtree, "platform:users:update");
        grantRole(resetSelf, "platform:mfa:reset");
        grantRole(updateAll, "platform:users:update");
        grantRole(roleRead, "platform:roles:read");
        grantRole(disabledRoleRead, "platform:roles:read");
        grantRole(disabledRoleRead, "platform:settings:read");
        grantRole(roleAssign, "platform:roles:assign");
        grantRole(writeOnly, "platform:users:write");
        grantRole(legacyReset, "platform:users:reset-password");
        grantRole(aiUpdateSelf, "platform:users:update");
        grantRole(aiToolsAll, "platform:users:create");
        grantRole(aiToolsAll, "platform:users:update");
        grantRole(aiToolsAll, "platform:users:delete");
        grantRole(dictionaryReadAll, "platform:dictionaries:read");
        grantRole(feedbackReadSelf, "platform:feedback:read");
        grantRole(feedbackReadAll, "platform:feedback:read-all");
        grantRole(feedbackUpdateSelf, "platform:feedback:update");
        grantRole(feedbackUpdateAll, "platform:feedback:update");
        grantRole(feedbackReplyAll, "platform:feedback:reply");

        assign(SCOPED_MFA_ACTOR, resetAll);
        assign(SCOPED_MFA_ACTOR, updateSelf);
        assign(SCOPED_MFA_PEER, resetAll);
        assign(SCOPED_MFA_PEER, updateSelf);
        assign(ORGANIZATION_MFA_ACTOR, resetAll);
        assign(ORGANIZATION_MFA_ACTOR, updateOrganization);
        assign(SUBTREE_MFA_ACTOR, resetAll);
        assign(SUBTREE_MFA_ACTOR, updateSubtree);
        assign(MIXED_MFA_ACTOR, resetAll);
        grantDirect(MIXED_MFA_ACTOR, "platform:users:update");
        assign(INVALID_MFA_ACTOR, resetSelf);
        assign(INVALID_MFA_ACTOR, updateAll);
        assign(POLICY_ACTOR, roleRead);
        grantDirect(POLICY_ACTOR, "platform:settings:read");
        grantDirect(DISABLED_POLICY_ACTOR, "platform:roles:read");
        grantDirect(DISABLED_POLICY_ACTOR, "platform:settings:read");
        assign(REGISTRATION_ACTOR, roleAssign);
        grantDirect(REGISTRATION_ACTOR, "platform:settings:update");
        assign(WRITE_ONLY_ACTOR, writeOnly);
        assign(LEGACY_RESET_ACTOR, legacyReset);
        assign(SCOPED_AI_ACTOR, aiUpdateSelf);
        assign(GLOBAL_AI_ACTOR, aiToolsAll);
        assign(POLICY_ACTOR, dictionaryReadAll);
        assign(DICTIONARY_ONLY_ACTOR, dictionaryReadAll);
        grantDirect(SCOPED_AI_ACTOR, "platform:settings:read");
        grantDirect(GLOBAL_AI_ACTOR, "platform:settings:read");
        grantDirect(GLOBAL_AI_NO_PAGE_ACTOR, "platform:users:create");
        grantDirect(GLOBAL_AI_NO_PAGE_ACTOR, "platform:users:update");
        grantDirect(GLOBAL_AI_NO_PAGE_ACTOR, "platform:users:delete");
        assign(FEEDBACK_WITHOUT_READ_ALL_ACTOR, feedbackReadSelf);
        assign(FEEDBACK_WITHOUT_READ_ALL_ACTOR, feedbackUpdateAll);
        assign(FEEDBACK_WITHOUT_READ_ALL_ACTOR, feedbackReplyAll);
        assign(FEEDBACK_MIXED_SCOPE_ACTOR, feedbackReadAll);
        assign(FEEDBACK_MIXED_SCOPE_ACTOR, feedbackUpdateSelf);
        grantDirect(FEEDBACK_MIXED_SCOPE_ACTOR, "platform:feedback:reply");
        assign(FEEDBACK_LEGACY_FULL_ACTOR, feedbackReadAll);
        grantDirect(FEEDBACK_LEGACY_FULL_ACTOR, "platform:feedback:update");
        assign(FEEDBACK_LEGACY_FULL_ACTOR, feedbackReplyAll);

        UUID organizationRoot = insertOrganization("permission-org-root", null);
        UUID organizationChild = insertOrganization("permission-org-child", organizationRoot);
        UUID unrelatedOrganization = insertOrganization("permission-org-unrelated", null);
        assignOrganization(ORGANIZATION_MFA_ACTOR, organizationRoot);
        assignOrganization(SUBTREE_MFA_ACTOR, organizationRoot);
        assignOrganization(ORGANIZATION_PEER, organizationRoot);
        assignOrganization(ORGANIZATION_CHILD_USER, organizationChild);
        assignOrganization(UNRELATED_ORGANIZATION_USER, unrelatedOrganization);
        jdbc.update("UPDATE platform_user SET enabled=false WHERE id=?", DISABLED_POLICY_ACTOR);
        jdbc.update("UPDATE platform_role SET enabled=false WHERE id=?", disabledRoleRead);
        flyway =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .schemas("public")
                        .defaultSchema("public")
                        .target("50")
                        .load();
        flyway.migrate();
        applyFeedbackPermissionMigration();
        captureV50Snapshots();

        // Unknown opaque grants are removed, and an already-retired raw mapping must not be
        // replayed because V50 had already made its original grant inert.
        grantDirect(OTHER_USER, "platform:__v50:unmapped-test-source");
        grantDirect(OTHER_USER, "platform:users:read");
        RETIRED_USERS_READ_EFFECTIVE = hasEffective(OTHER_USER, "platform:users:read");
        RETIRED_USERS_READ_GLOBAL = hasGlobal(OTHER_USER, "platform:users:read");
        assertFalse(RETIRED_USERS_READ_EFFECTIVE);
        assertFalse(RETIRED_USERS_READ_GLOBAL);
        flyway =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .schemas("public")
                        .defaultSchema("public")
                        .load();
        flyway.migrate();
        assertEquals("51", flyway.info().current().getVersion().getVersion());

        UUID explicitReset = insertRole("permission-explicit-reset", "SELF");
        grantRole(explicitReset, "platform:users:access");
        grantRole(explicitReset, "platform:users:reset-password");
        assign(EXPLICIT_RESET_ACTOR, explicitReset);
    }

    @Test
    void preservesMixedMfaSourcesButKeepsTargetScopeAndGlobalPrerequisiteSeparate() {
        assertTrue(hasEffective(SCOPED_MFA_ACTOR, MFA_RESET));
        assertTrue(hasEffective(MIXED_MFA_ACTOR, MFA_RESET));
        assertFalse(hasGlobal(SCOPED_MFA_ACTOR, MFA_RESET));
        assertTrue(hasGlobal(MIXED_MFA_ACTOR, MFA_RESET));
        assertTrue(canTarget(SCOPED_MFA_ACTOR, MFA_RESET, SCOPED_MFA_ACTOR));
        assertTrue(canTarget(MIXED_MFA_ACTOR, MFA_RESET, MIXED_MFA_ACTOR));
        assertFalse(canTarget(SCOPED_MFA_ACTOR, MFA_RESET, OTHER_USER));
        assertTrue(canTarget(MIXED_MFA_ACTOR, MFA_RESET, OTHER_USER));
        assertFalse(hasEffective(INVALID_MFA_ACTOR, MFA_RESET));
        assertFalse(hasGlobal(INVALID_MFA_ACTOR, MFA_RESET));

        assertTrue(hasEffective(ORGANIZATION_MFA_ACTOR, MFA_RESET));
        assertFalse(hasGlobal(ORGANIZATION_MFA_ACTOR, MFA_RESET));
        assertTrue(canTarget(ORGANIZATION_MFA_ACTOR, MFA_RESET, ORGANIZATION_MFA_ACTOR));
        assertTrue(canTarget(ORGANIZATION_MFA_ACTOR, MFA_RESET, ORGANIZATION_PEER));
        assertFalse(canTarget(ORGANIZATION_MFA_ACTOR, MFA_RESET, ORGANIZATION_CHILD_USER));
        assertFalse(canTarget(ORGANIZATION_MFA_ACTOR, MFA_RESET, UNRELATED_ORGANIZATION_USER));

        assertTrue(hasEffective(SUBTREE_MFA_ACTOR, MFA_RESET));
        assertFalse(hasGlobal(SUBTREE_MFA_ACTOR, MFA_RESET));
        assertTrue(canTarget(SUBTREE_MFA_ACTOR, MFA_RESET, SUBTREE_MFA_ACTOR));
        assertTrue(canTarget(SUBTREE_MFA_ACTOR, MFA_RESET, ORGANIZATION_PEER));
        assertTrue(canTarget(SUBTREE_MFA_ACTOR, MFA_RESET, ORGANIZATION_CHILD_USER));
        assertFalse(canTarget(SUBTREE_MFA_ACTOR, MFA_RESET, UNRELATED_ORGANIZATION_USER));

        assertFalse(hasEffective(SCOPED_MFA_ACTOR, "platform:mfa:reset"));
        assertFalse(hasEffective(MIXED_MFA_ACTOR, "platform:mfa:reset"));
    }

    @Test
    void preservesEffectiveGlobalAndTargetDecisionsFromV50() {
        assertFalse(V50_SNAPSHOTS.isEmpty());
        for (AuthorizationSnapshot expected : V50_SNAPSHOTS) {
            assertEquals(
                    expected.effective(),
                    hasEffective(expected.actor(), expected.authority()),
                    "Effective decision changed for "
                            + expected.actor()
                            + " / "
                            + expected.authority());
            assertEquals(
                    expected.global(),
                    hasGlobal(expected.actor(), expected.authority()),
                    "Global decision changed for "
                            + expected.actor()
                            + " / "
                            + expected.authority());
            assertEquals(
                    expected.visible(),
                    canTarget(expected.actor(), expected.authority(), expected.target()),
                    "Target visibility changed for "
                            + expected.actor()
                            + " / "
                            + expected.authority()
                            + " / "
                            + expected.target());
        }
    }

    @Test
    void alreadyInertRetiredRawGrantDoesNotReplayItsV50Mapping() {
        assertEquals(RETIRED_USERS_READ_EFFECTIVE, hasEffective(OTHER_USER, "platform:users:read"));
        assertEquals(RETIRED_USERS_READ_GLOBAL, hasGlobal(OTHER_USER, "platform:users:read"));
        assertFalse(canTarget(OTHER_USER, "platform:users:read", OTHER_USER));
        assertFalse(hasEffective(OTHER_USER, "platform:users:access"));
        assertFalse(hasEffective(OTHER_USER, "platform:users:export"));
        assertFalse(hasEffective(OTHER_USER, "platform:online-users:access"));
        assertFalse(hasEffective(OTHER_USER, "platform:ai-console:read-users"));
        assertEquals(4, archiveRows("platform:users:read", OTHER_USER));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_permission_migration_archive "
                                + "WHERE source_authority='platform:users:read' "
                                + "AND principal_kind='USER' AND principal_id=? "
                                + "AND normalized_authority IS NOT NULL",
                        Integer.class,
                        OTHER_USER));
    }

    @Test
    void derivesCompoundPolicyAndRegistrationActionsAcrossDirectAndRoleGrants() {
        assertTrue(hasEffective(POLICY_ACTOR, ROLE_POLICY_READ));
        assertTrue(hasGlobal(POLICY_ACTOR, ROLE_POLICY_READ));
        assertFalse(roleHas(assignedRole(POLICY_ACTOR, "permission-role-read"), ROLE_POLICY_READ));
        assertTrue(hasEffective(REGISTRATION_ACTOR, REGISTRATION_ASSIGN));
        assertTrue(hasGlobal(REGISTRATION_ACTOR, REGISTRATION_ASSIGN));
        assertFalse(hasEffective(REGISTRATION_ACTOR, "platform:registration:access"));
        assertFalse(hasEffective(REGISTRATION_ACTOR, "platform:settings:update"));
        assertFalse(hasEffective(REGISTRATION_ACTOR, "platform:roles:assign"));
    }

    @Test
    void keepsProfileFieldDictionaryActionAndPageGatesIndependent() {
        assertTrue(hasEffective(POLICY_ACTOR, "platform:profile-fields:access"));
        assertTrue(hasEffective(POLICY_ACTOR, "platform:profile-fields:read-dictionaries"));
        assertTrue(hasGlobal(POLICY_ACTOR, "platform:profile-fields:read-dictionaries"));

        assertTrue(hasEffective(SCOPED_AI_ACTOR, "platform:profile-fields:access"));
        assertFalse(hasEffective(SCOPED_AI_ACTOR, "platform:profile-fields:read-dictionaries"));

        assertTrue(
                hasEffective(DICTIONARY_ONLY_ACTOR, "platform:profile-fields:read-dictionaries"));
        assertTrue(hasGlobal(DICTIONARY_ONLY_ACTOR, "platform:profile-fields:read-dictionaries"));
        assertFalse(hasEffective(DICTIONARY_ONLY_ACTOR, "platform:profile-fields:access"));
    }

    @Test
    void keepsWriteOnlyActionsIndependentFromUserAndAiConsolePageAccess() {
        assertTrue(hasEffective(WRITE_ONLY_ACTOR, "platform:users:create"));
        assertFalse(hasEffective(WRITE_ONLY_ACTOR, "platform:users:access"));
        assertFalse(hasEffective(WRITE_ONLY_ACTOR, "platform:users:read"));
        assertFalse(hasEffective(WRITE_ONLY_ACTOR, "platform:users:write"));
        assertFalse(hasEffective(WRITE_ONLY_ACTOR, AI_CONSOLE_ACCESS));
        // V50 fans the legacy write bundle out to ordinary user CRUD grants, but its hidden AI
        // operation sources are copied only from the specific old CRUD grants. Do not infer AI
        // tool authority from the broad, unrelated users:write source.
        assertFalse(hasEffective(WRITE_ONLY_ACTOR, AI_CONSOLE_CREATE_USER));
        assertFalse(hasEffective(WRITE_ONLY_ACTOR, AI_CONSOLE_UPDATE_USER));
        assertFalse(hasEffective(WRITE_ONLY_ACTOR, AI_CONSOLE_DELETE_USER));
        assertFalse(hasGlobal(WRITE_ONLY_ACTOR, AI_CONSOLE_CREATE_USER));
        assertFalse(hasGlobal(WRITE_ONLY_ACTOR, AI_CONSOLE_UPDATE_USER));
        assertFalse(hasGlobal(WRITE_ONLY_ACTOR, AI_CONSOLE_DELETE_USER));
    }

    @Test
    void normalizesAiToolsFromMatchingGlobalUserActionsIndependentlyFromPageAccess() {
        assertTrue(hasEffective(SCOPED_AI_ACTOR, AI_CONSOLE_ACCESS));
        assertFalse(hasEffective(SCOPED_AI_ACTOR, AI_CONSOLE_UPDATE_USER));
        assertFalse(hasGlobal(SCOPED_AI_ACTOR, AI_CONSOLE_UPDATE_USER));

        assertTrue(hasEffective(GLOBAL_AI_ACTOR, AI_CONSOLE_ACCESS));
        assertTrue(hasEffective(GLOBAL_AI_ACTOR, AI_CONSOLE_CREATE_USER));
        assertTrue(hasEffective(GLOBAL_AI_ACTOR, AI_CONSOLE_UPDATE_USER));
        assertTrue(hasEffective(GLOBAL_AI_ACTOR, AI_CONSOLE_DELETE_USER));
        assertTrue(hasGlobal(GLOBAL_AI_ACTOR, AI_CONSOLE_CREATE_USER));
        assertTrue(hasGlobal(GLOBAL_AI_ACTOR, AI_CONSOLE_UPDATE_USER));
        assertTrue(hasGlobal(GLOBAL_AI_ACTOR, AI_CONSOLE_DELETE_USER));

        // A GLOBAL users:update source gives the legacy AI operation. The new AI page grant is
        // still independently controlled by settings:read.
        assertFalse(hasEffective(MIXED_MFA_ACTOR, AI_CONSOLE_ACCESS));
        assertTrue(hasGlobal(MIXED_MFA_ACTOR, AI_CONSOLE_UPDATE_USER));

        assertFalse(hasEffective(GLOBAL_AI_NO_PAGE_ACTOR, AI_CONSOLE_ACCESS));
        assertTrue(hasEffective(GLOBAL_AI_NO_PAGE_ACTOR, AI_CONSOLE_CREATE_USER));
        assertTrue(hasEffective(GLOBAL_AI_NO_PAGE_ACTOR, AI_CONSOLE_UPDATE_USER));
        assertTrue(hasEffective(GLOBAL_AI_NO_PAGE_ACTOR, AI_CONSOLE_DELETE_USER));
    }

    @Test
    void allowsAnExplicitNewScopedResetGrantDespiteTheSameLegacyCodeName() {
        assertTrue(hasEffective(EXPLICIT_RESET_ACTOR, "platform:users:access"));
        assertTrue(hasEffective(EXPLICIT_RESET_ACTOR, "platform:users:reset-password"));
        assertFalse(hasGlobal(EXPLICIT_RESET_ACTOR, "platform:users:reset-password"));
        assertTrue(
                canTarget(
                        EXPLICIT_RESET_ACTOR,
                        "platform:users:reset-password",
                        EXPLICIT_RESET_ACTOR));
        assertFalse(canTarget(EXPLICIT_RESET_ACTOR, "platform:users:reset-password", OTHER_USER));
        assertTrue(
                roleHas(
                        assignedRole(EXPLICIT_RESET_ACTOR, "permission-explicit-reset"),
                        "platform:users:reset-password"));
    }

    @Test
    void retiresProjectionSourcesAndPreservesRoleLocalAndUserResidualGrants() {
        UUID policyRole = assignedRole(POLICY_ACTOR, "permission-role-read");
        assertFalse(roleHas(policyRole, ROLE_POLICY_READ));
        assertTrue(hasEffective(POLICY_ACTOR, ROLE_POLICY_READ));
        assertTrue(hasGlobal(POLICY_ACTOR, ROLE_POLICY_READ));
        assertEquals(1, countUserRoleAuthority(POLICY_ACTOR, ROLE_POLICY_READ));

        UUID resetRole = assignedRole(LEGACY_RESET_ACTOR, "permission-legacy-reset");
        assertFalse(roleHas(resetRole, "platform:users:reset-password"));
        assertFalse(hasEffective(LEGACY_RESET_ACTOR, "platform:users:reset-password"));

        UUID disabledRole =
                jdbc.queryForObject(
                        "SELECT id FROM platform_role WHERE code='permission-role-read-disabled'",
                        UUID.class);
        assertFalse(
                jdbc.queryForObject(
                        "SELECT enabled FROM platform_role WHERE id=?",
                        Boolean.class,
                        disabledRole));
        assertTrue(roleHas(disabledRole, ROLE_POLICY_READ));

        assertTrue(hasEffective(DISABLED_POLICY_ACTOR, ROLE_POLICY_READ));
        assertTrue(hasGlobal(DISABLED_POLICY_ACTOR, ROLE_POLICY_READ));
        assertFalse(
                jdbc.queryForObject(
                        "SELECT enabled FROM platform_user WHERE id=?",
                        Boolean.class,
                        DISABLED_POLICY_ACTOR));

        UUID scopedResidual = userRoleWithAuthority(SCOPED_MFA_ACTOR, MFA_RESET);
        UUID peerResidual = userRoleWithAuthority(SCOPED_MFA_PEER, MFA_RESET);
        assertNotEquals(scopedResidual, peerResidual);
        assertEquals(1, assignedUserCount(scopedResidual));
        assertEquals(1, assignedUserCount(peerResidual));
        assertNoOpaqueRuntimeSources();
        assertEquals(1, archiveRows("platform:__v50:unmapped-test-source", OTHER_USER));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_permission_migration_archive "
                                + "WHERE source_authority='platform:__v50:unmapped-test-source' "
                                + "AND principal_kind='USER' AND principal_id=? "
                                + "AND logical_authority='' AND normalized_authority IS NULL",
                        Integer.class,
                        OTHER_USER));
    }

    @Test
    void migratesMenuPermissionKeysToTheirPageOwners() {
        assertEquals(
                "platform:users:access",
                jdbc.queryForObject(
                        "SELECT permission_key FROM platform_navigation_menu "
                                + "WHERE path='/dashboard/users' AND menu_type='PAGE'",
                        String.class));
        assertEquals(
                "platform:ai-console:access",
                jdbc.queryForObject(
                        "SELECT permission_key FROM platform_navigation_menu "
                                + "WHERE path='/dashboard/ai-console' AND menu_type='PAGE'",
                        String.class));
    }

    @Test
    void feedbackLegacyActionsKeepTheGlobalReadAllPrerequisiteButNewActionsStayIndependent() {
        assertTrue(hasEffective(FEEDBACK_WITHOUT_READ_ALL_ACTOR, FEEDBACK_ACCESS));
        assertTrue(hasEffective(FEEDBACK_WITHOUT_READ_ALL_ACTOR, FEEDBACK_READ));
        assertFalse(hasEffective(FEEDBACK_WITHOUT_READ_ALL_ACTOR, FEEDBACK_READ_ALL));
        assertFalse(hasEffective(FEEDBACK_WITHOUT_READ_ALL_ACTOR, FEEDBACK_UPDATE));
        assertFalse(hasGlobal(FEEDBACK_WITHOUT_READ_ALL_ACTOR, FEEDBACK_UPDATE));
        assertFalse(hasEffective(FEEDBACK_WITHOUT_READ_ALL_ACTOR, FEEDBACK_REPLY));
        assertFalse(hasGlobal(FEEDBACK_WITHOUT_READ_ALL_ACTOR, FEEDBACK_REPLY));

        assertTrue(hasGlobal(FEEDBACK_MIXED_SCOPE_ACTOR, FEEDBACK_READ_ALL));
        assertFalse(hasEffective(FEEDBACK_MIXED_SCOPE_ACTOR, FEEDBACK_UPDATE));
        assertFalse(hasGlobal(FEEDBACK_MIXED_SCOPE_ACTOR, FEEDBACK_UPDATE));
        assertTrue(hasGlobal(FEEDBACK_MIXED_SCOPE_ACTOR, FEEDBACK_REPLY));

        assertTrue(hasGlobal(FEEDBACK_LEGACY_FULL_ACTOR, FEEDBACK_READ_ALL));
        assertTrue(hasGlobal(FEEDBACK_LEGACY_FULL_ACTOR, FEEDBACK_UPDATE));
        assertTrue(hasGlobal(FEEDBACK_LEGACY_FULL_ACTOR, FEEDBACK_REPLY));
        assertFalse(hasEffective(FEEDBACK_LEGACY_FULL_ACTOR, "platform:feedback:manage"));

        grantDirect(FEEDBACK_NEW_ACTIONS_ACTOR, FEEDBACK_ACCESS);
        grantDirect(FEEDBACK_NEW_ACTIONS_ACTOR, FEEDBACK_UPDATE);
        grantDirect(FEEDBACK_NEW_ACTIONS_ACTOR, FEEDBACK_REPLY);
        assertFalse(hasEffective(FEEDBACK_NEW_ACTIONS_ACTOR, FEEDBACK_READ_ALL));
        assertTrue(hasGlobal(FEEDBACK_NEW_ACTIONS_ACTOR, FEEDBACK_UPDATE));
        assertTrue(hasGlobal(FEEDBACK_NEW_ACTIONS_ACTOR, FEEDBACK_REPLY));
    }

    @Test
    void administrationGuardUsesNormalGrantGlobalAuthorization() {
        assertTrue(hasGlobal(POLICY_ACTOR, ROLE_POLICY_READ));
        assertTrue(administrationGuard.hasGlobal(username(POLICY_ACTOR), ROLE_POLICY_READ));
        administrationGuard.requireGlobal(username(POLICY_ACTOR), ROLE_POLICY_READ);

        assertFalse(hasGlobal(SCOPED_MFA_ACTOR, MFA_RESET));
        assertFalse(administrationGuard.hasGlobal(username(SCOPED_MFA_ACTOR), MFA_RESET));
        assertThrows(
                ResponseStatusException.class,
                () -> administrationGuard.requireGlobal(username(SCOPED_MFA_ACTOR), MFA_RESET));

        assertTrue(hasGlobal(FEEDBACK_LEGACY_FULL_ACTOR, FEEDBACK_UPDATE));
        assertTrue(
                administrationGuard.hasGlobal(
                        username(FEEDBACK_LEGACY_FULL_ACTOR), FEEDBACK_UPDATE));
        administrationGuard.requireGlobal(username(FEEDBACK_LEGACY_FULL_ACTOR), FEEDBACK_UPDATE);
        assertFalse(hasGlobal(FEEDBACK_MIXED_SCOPE_ACTOR, FEEDBACK_UPDATE));
        assertFalse(
                administrationGuard.hasGlobal(
                        username(FEEDBACK_MIXED_SCOPE_ACTOR), FEEDBACK_UPDATE));
        assertThrows(
                ResponseStatusException.class,
                () ->
                        administrationGuard.requireGlobal(
                                username(FEEDBACK_MIXED_SCOPE_ACTOR), FEEDBACK_UPDATE));

        grantDirect(OTHER_USER, "platform:users:write");
        assertFalse(hasGlobal(OTHER_USER, "platform:users:write"));
        assertFalse(administrationGuard.hasGlobal(username(OTHER_USER), "platform:users:write"));
        assertThrows(
                ResponseStatusException.class,
                () ->
                        administrationGuard.requireGlobal(
                                username(OTHER_USER), "platform:users:write"));
    }

    @Test
    void normalizerRejectsScopedNontargetMetadataThatWouldExpandVisibility() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            var savepoint = connection.setSavepoint();
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO platform_permission_projection_component "
                                + "(logical_authority,source_authority,source_requires_global,target_scope_source) "
                                + "VALUES ('platform:test:non-target','platform:__v50:test-nontarget',false,false)");
                UUID roleId = UUID.randomUUID();
                try (var insertRole =
                        connection.prepareStatement(
                                "INSERT INTO platform_role(id,code,name,data_scope) VALUES (?,?,?,'SELF')")) {
                    insertRole.setObject(1, roleId);
                    insertRole.setString(2, "permission-unsupported-nontarget");
                    insertRole.setString(3, "permission-unsupported-nontarget");
                    insertRole.executeUpdate();
                }
                try (var insertGrant =
                        connection.prepareStatement(
                                "INSERT INTO platform_role_authority(role_id,authority) VALUES (?,?)")) {
                    insertGrant.setObject(1, roleId);
                    insertGrant.setString(2, "platform:__v50:test-nontarget");
                    insertGrant.executeUpdate();
                }
                assertThrows(
                        java.sql.SQLException.class,
                        () ->
                                statement.execute(
                                        "SELECT platform_normalize_legacy_permission_projections()"));
            } finally {
                connection.rollback(savepoint);
                connection.commit();
            }
        }
    }

    @Test
    void normalizerRejectsAllTargetVisibilityWhenGlobalPrerequisiteWasOnlyScoped()
            throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            var savepoint = connection.setSavepoint();
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO platform_permission_projection_component "
                                + "(logical_authority,source_authority,source_requires_global,target_scope_source) "
                                + "VALUES "
                                + "('platform:test:mixed-scope-global','platform:__v50:test-target-global',false,true),"
                                + "('platform:test:mixed-scope-global','platform:__v50:test-scoped-prerequisite',false,false)");

                UUID actorId = UUID.randomUUID();
                try (var insertUser =
                        connection.prepareStatement(
                                "INSERT INTO platform_user(id,username,display_name,password_hash) "
                                        + "VALUES (?,?,?,'unused')")) {
                    insertUser.setObject(1, actorId);
                    insertUser.setString(2, "permission-unsupported-mixed-global");
                    insertUser.setString(3, "permission-unsupported-mixed-global");
                    insertUser.executeUpdate();
                }

                UUID scopedRoleId = UUID.randomUUID();
                try (var insertRole =
                        connection.prepareStatement(
                                "INSERT INTO platform_role(id,code,name,data_scope) VALUES (?,?,?,'SELF')")) {
                    insertRole.setObject(1, scopedRoleId);
                    insertRole.setString(2, "permission-unsupported-mixed-prerequisite");
                    insertRole.setString(3, "permission-unsupported-mixed-prerequisite");
                    insertRole.executeUpdate();
                }
                try (var insertRoleGrant =
                        connection.prepareStatement(
                                "INSERT INTO platform_role_authority(role_id,authority) VALUES (?,?)")) {
                    insertRoleGrant.setObject(1, scopedRoleId);
                    insertRoleGrant.setString(2, "platform:__v50:test-scoped-prerequisite");
                    insertRoleGrant.executeUpdate();
                }
                try (var assignRole =
                        connection.prepareStatement(
                                "INSERT INTO platform_user_role(user_id,role_id) VALUES (?,?)")) {
                    assignRole.setObject(1, actorId);
                    assignRole.setObject(2, scopedRoleId);
                    assignRole.executeUpdate();
                }
                try (var insertDirectGrant =
                        connection.prepareStatement(
                                "INSERT INTO platform_authority(user_id,authority) VALUES (?,?)")) {
                    insertDirectGrant.setObject(1, actorId);
                    insertDirectGrant.setString(2, "platform:__v50:test-target-global");
                    insertDirectGrant.executeUpdate();
                }

                assertThrows(
                        java.sql.SQLException.class,
                        () ->
                                statement.execute(
                                        "SELECT platform_normalize_legacy_permission_projections()"));
            } finally {
                connection.rollback(savepoint);
                connection.commit();
            }
        }
    }

    @Test
    void normalizerPreservesNarrowTargetScopeWhenAnotherPrerequisiteIsNarrower() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            var savepoint = connection.setSavepoint();
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO platform_permission_projection_component "
                                + "(logical_authority,source_authority,source_requires_global,target_scope_source) "
                                + "VALUES "
                                + "('platform:test:scoped-prerequisite','platform:__v50:test-subtree-target',false,true),"
                                + "('platform:test:scoped-prerequisite','platform:__v50:test-self-prerequisite',false,false)");

                UUID actorId = UUID.randomUUID();
                String actorName = "permission-narrow-target-scope";
                try (var insertUser =
                        connection.prepareStatement(
                                "INSERT INTO platform_user(id,username,display_name,password_hash) "
                                        + "VALUES (?,?,?,'unused')")) {
                    insertUser.setObject(1, actorId);
                    insertUser.setString(2, actorName);
                    insertUser.setString(3, actorName);
                    insertUser.executeUpdate();
                }

                UUID targetRoleId = UUID.randomUUID();
                UUID prerequisiteRoleId = UUID.randomUUID();
                try (var insertRole =
                        connection.prepareStatement(
                                "INSERT INTO platform_role(id,code,name,data_scope) VALUES (?,?,?,?)")) {
                    insertRole.setObject(1, targetRoleId);
                    insertRole.setString(2, "permission-narrow-target-source");
                    insertRole.setString(3, "permission-narrow-target-source");
                    insertRole.setString(4, "SUBTREE");
                    insertRole.executeUpdate();
                    insertRole.setObject(1, prerequisiteRoleId);
                    insertRole.setString(2, "permission-narrow-prerequisite-source");
                    insertRole.setString(3, "permission-narrow-prerequisite-source");
                    insertRole.setString(4, "SELF");
                    insertRole.executeUpdate();
                }
                try (var insertGrant =
                        connection.prepareStatement(
                                "INSERT INTO platform_role_authority(role_id,authority) VALUES (?,?)")) {
                    insertGrant.setObject(1, targetRoleId);
                    insertGrant.setString(2, "platform:__v50:test-subtree-target");
                    insertGrant.executeUpdate();
                    insertGrant.setObject(1, prerequisiteRoleId);
                    insertGrant.setString(2, "platform:__v50:test-self-prerequisite");
                    insertGrant.executeUpdate();
                }
                try (var assignRole =
                        connection.prepareStatement(
                                "INSERT INTO platform_user_role(user_id,role_id) VALUES (?,?)")) {
                    assignRole.setObject(1, actorId);
                    assignRole.setObject(2, targetRoleId);
                    assignRole.executeUpdate();
                    assignRole.setObject(1, actorId);
                    assignRole.setObject(2, prerequisiteRoleId);
                    assignRole.executeUpdate();
                }

                statement.execute("SELECT platform_normalize_legacy_permission_projections()");
                assertTrue(
                        queryBoolean(
                                connection,
                                "SELECT EXISTS(SELECT 1 FROM platform_effective_authority "
                                        + "WHERE user_id=? AND authority='platform:test:scoped-prerequisite')",
                                actorId));
                assertFalse(
                        queryBoolean(
                                connection,
                                "SELECT EXISTS(SELECT 1 FROM platform_global_authority "
                                        + "WHERE user_id=? AND authority='platform:test:scoped-prerequisite')",
                                actorId));
                assertTrue(
                        queryBoolean(
                                connection,
                                "SELECT EXISTS(SELECT 1 FROM platform_visible_users(?,?) "
                                        + "WHERE user_id=?)",
                                actorName,
                                "platform:test:scoped-prerequisite",
                                actorId));
                assertFalse(
                        queryBoolean(
                                connection,
                                "SELECT EXISTS(SELECT 1 FROM platform_visible_users(?,?) "
                                        + "WHERE user_id=?)",
                                actorName,
                                "platform:test:scoped-prerequisite",
                                OTHER_USER));
            } finally {
                connection.rollback(savepoint);
                connection.commit();
            }
        }
    }

    @Test
    void roleAuthenticationPolicyGuardRequiresItsOwnGlobalPageAndOperation() {
        String rolePage = "platform:roles:access";
        String settingsPage = "platform:settings:access";
        String readPolicy = "platform:roles:read-authentication-policy";
        String updatePolicy = "platform:roles:update-authentication-policy";
        UUID targetRoleId = assignedRole(POLICY_ACTOR, "permission-role-read");
        RoleAuthenticationPolicyService policyService = roleAuthenticationPolicyService();

        UUID writer = UUID.randomUUID();
        insertUser(writer, "permission-policy-writer-init");
        grantDirect(writer, rolePage);
        grantDirect(writer, updatePolicy);
        assertTrue(administrationGuard.hasGlobal(username(writer), updatePolicy));
        assertFalse(administrationGuard.hasGlobal(username(writer), readPolicy));
        assertEquals(targetRoleId, policyService.read(targetRoleId, username(writer)).roleId());

        UUID pageOnly = UUID.randomUUID();
        insertUser(pageOnly, "permission-policy-page-only");
        grantDirect(pageOnly, rolePage);
        assertTrue(administrationGuard.hasGlobal(username(pageOnly), rolePage));
        assertFalse(administrationGuard.hasGlobal(username(pageOnly), readPolicy));
        assertFalse(administrationGuard.hasGlobal(username(pageOnly), updatePolicy));
        assertThrows(
                ResponseStatusException.class,
                () -> administrationGuard.requireGlobal(username(pageOnly), readPolicy));
        assertThrows(
                ResponseStatusException.class,
                () -> policyService.read(targetRoleId, username(pageOnly)));

        UUID siblingPage = UUID.randomUUID();
        insertUser(siblingPage, "permission-policy-sibling-page");
        grantDirect(siblingPage, settingsPage);
        grantDirect(siblingPage, updatePolicy);
        assertTrue(administrationGuard.hasGlobal(username(siblingPage), updatePolicy));
        assertFalse(administrationGuard.hasGlobal(username(siblingPage), rolePage));
        assertThrows(
                ResponseStatusException.class,
                () -> administrationGuard.requireGlobal(username(siblingPage), rolePage));
        assertThrows(
                ResponseStatusException.class,
                () -> policyService.read(targetRoleId, username(siblingPage)));

        UUID noPermissions = UUID.randomUUID();
        insertUser(noPermissions, "permission-policy-no-permissions");
        assertFalse(administrationGuard.hasGlobal(username(noPermissions), rolePage));
        assertFalse(administrationGuard.hasGlobal(username(noPermissions), readPolicy));
        assertFalse(administrationGuard.hasGlobal(username(noPermissions), updatePolicy));
        assertThrows(
                ResponseStatusException.class,
                () -> policyService.read(targetRoleId, username(noPermissions)));

        UUID selfScoped = UUID.randomUUID();
        insertUser(selfScoped, "permission-policy-self-scoped");
        grantDirect(selfScoped, rolePage);
        UUID selfPolicyWriter = insertRole("permission-policy-self-writer", "SELF");
        grantRole(selfPolicyWriter, updatePolicy);
        assign(selfScoped, selfPolicyWriter);
        assertTrue(hasEffective(selfScoped, updatePolicy));
        assertFalse(administrationGuard.hasGlobal(username(selfScoped), updatePolicy));
        assertThrows(
                ResponseStatusException.class,
                () -> administrationGuard.requireGlobal(username(selfScoped), updatePolicy));
        assertThrows(
                ResponseStatusException.class,
                () -> policyService.read(targetRoleId, username(selfScoped)));
    }

    @SuppressWarnings("unchecked")
    private static RoleAuthenticationPolicyService roleAuthenticationPolicyService() {
        JdbcClient client = JdbcClient.create(jdbc.getDataSource());
        ObjectProvider<CaptchaProofPort> captcha = mock(ObjectProvider.class);
        return new RoleAuthenticationPolicyService(
                client,
                new RoleAuthenticationPolicyRepository(client),
                administrationGuard,
                mock(AuditPort.class),
                mock(GlobalAuthenticationPolicyPort.class),
                mock(AuthenticationReachabilityPort.class),
                mock(AuthenticationPolicyWriteLock.class),
                mock(PluginExtensionsPort.class),
                captcha,
                mock(AuthenticationMethodAvailabilityPort.class));
    }

    private static void captureV50Snapshots() {
        for (UUID actor :
                List.of(
                        SCOPED_MFA_ACTOR,
                        MIXED_MFA_ACTOR,
                        INVALID_MFA_ACTOR,
                        ORGANIZATION_MFA_ACTOR,
                        SUBTREE_MFA_ACTOR)) {
            captureSnapshots(actor, MFA_RESET);
        }
        captureSnapshots(POLICY_ACTOR, ROLE_POLICY_READ);
        captureSnapshots(REGISTRATION_ACTOR, REGISTRATION_ASSIGN);
        captureSnapshots(LEGACY_RESET_ACTOR, "platform:users:reset-password");

        for (String authority :
                List.of(AI_CONSOLE_CREATE_USER, AI_CONSOLE_UPDATE_USER, AI_CONSOLE_DELETE_USER)) {
            captureSnapshots(SCOPED_AI_ACTOR, authority);
            captureSnapshots(GLOBAL_AI_ACTOR, authority);
            captureSnapshots(GLOBAL_AI_NO_PAGE_ACTOR, authority);
            captureSnapshots(WRITE_ONLY_ACTOR, authority);
        }

        for (String authority : List.of(FEEDBACK_READ_ALL, FEEDBACK_UPDATE, FEEDBACK_REPLY)) {
            captureSnapshots(FEEDBACK_WITHOUT_READ_ALL_ACTOR, authority);
            captureSnapshots(FEEDBACK_MIXED_SCOPE_ACTOR, authority);
            captureSnapshots(FEEDBACK_LEGACY_FULL_ACTOR, authority);
        }
    }

    private static void captureSnapshots(UUID actor, String authority) {
        for (UUID target :
                List.of(
                        actor,
                        ORGANIZATION_PEER,
                        ORGANIZATION_CHILD_USER,
                        UNRELATED_ORGANIZATION_USER,
                        OTHER_USER)) {
            V50_SNAPSHOTS.add(
                    new AuthorizationSnapshot(
                            actor,
                            authority,
                            target,
                            hasEffective(actor, authority),
                            hasGlobal(actor, authority),
                            canTarget(actor, authority, target)));
        }
    }

    private static int countUserRoleAuthority(UUID userId, String authority) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM platform_user_role assigned "
                        + "JOIN platform_role_authority grant_row ON grant_row.role_id=assigned.role_id "
                        + "WHERE assigned.user_id=? AND grant_row.authority=?",
                Integer.class,
                userId,
                authority);
    }

    private static UUID userRoleWithAuthority(UUID userId, String authority) {
        return jdbc.queryForObject(
                "SELECT role.id FROM platform_user_role assigned "
                        + "JOIN platform_role role ON role.id=assigned.role_id "
                        + "JOIN platform_role_authority grant_row ON grant_row.role_id=role.id "
                        + "WHERE assigned.user_id=? AND grant_row.authority=? "
                        + "AND role.code LIKE 'migrated-permissions-%'",
                UUID.class, userId, authority);
    }

    private static int assignedUserCount(UUID roleId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM platform_user_role WHERE role_id=?", Integer.class, roleId);
    }

    private static int archiveRows(String sourceAuthority, UUID principalId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM platform_permission_migration_archive "
                        + "WHERE source_authority=? AND principal_kind='USER' AND principal_id=?",
                Integer.class,
                sourceAuthority,
                principalId);
    }

    private static boolean queryBoolean(
            java.sql.Connection connection, String sql, Object... parameters)
            throws java.sql.SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static void assertNoOpaqueRuntimeSources() {
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT (SELECT count(*) FROM platform_role_authority "
                                + "WHERE authority LIKE 'platform:__v50:%') + "
                                + "(SELECT count(*) FROM platform_authority "
                                + "WHERE authority LIKE 'platform:__v50:%')",
                        Integer.class));
    }

    private record AuthorizationSnapshot(
            UUID actor,
            String authority,
            UUID target,
            boolean effective,
            boolean global,
            boolean visible) {}

    private static UUID id(String value) {
        return UUID.fromString(value);
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

    private static UUID insertOrganization(String code, UUID parentId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_organization(id,parent_id,code,name) VALUES (?,?,?,?)",
                id,
                parentId,
                code,
                code);
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

    private static void assignOrganization(UUID userId, UUID organizationId) {
        jdbc.update(
                "INSERT INTO platform_user_organization(user_id,organization_id) VALUES (?,?)",
                userId,
                organizationId);
    }

    private static void applyFeedbackPermissionMigration() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path migration =
                    directory.resolve(
                            "plugins/feedback/migrations/V2__independent_page_operations.sql");
            if (Files.isRegularFile(migration)) {
                try (var connection = jdbc.getDataSource().getConnection()) {
                    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
                            connection,
                            new org.springframework.core.io.FileSystemResource(migration.toFile()));
                    return;
                } catch (java.sql.SQLException error) {
                    throw new IllegalStateException(
                            "Feedback permission migration could not be applied", error);
                }
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Feedback permission migration was not found");
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

    private static boolean canTarget(UUID actor, String authority, UUID target) {
        return jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform_visible_users(?,?) WHERE user_id=?)",
                Boolean.class,
                username(actor),
                authority,
                target);
    }

    private static boolean roleHas(UUID roleId, String authority) {
        return jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform_role_logical_authorities(?) WHERE authority=?)",
                Boolean.class,
                roleId,
                authority);
    }

    private static UUID assignedRole(UUID userId, String roleCode) {
        return jdbc.queryForObject(
                "SELECT role.id FROM platform_user_role assigned "
                        + "JOIN platform_role role ON role.id=assigned.role_id "
                        + "WHERE assigned.user_id=? AND role.code=?",
                UUID.class,
                userId,
                roleCode);
    }

    private static String username(UUID id) {
        return jdbc.queryForObject(
                "SELECT username FROM platform_user WHERE id=?", String.class, id);
    }
}
