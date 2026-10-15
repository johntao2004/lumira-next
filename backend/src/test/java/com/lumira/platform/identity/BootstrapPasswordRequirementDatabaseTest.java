package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestSchema;
import com.lumira.platform.roles.BootstrapAdministratorRoleAdapter;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Bootstrap creation and compatibility checks against a disposable PostgreSQL schema. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class BootstrapPasswordRequirementDatabaseTest {
    private static final String BOOTSTRAP_PASSWORD = "Initial-bootstrap-password-8172!";
    private static final UUID ADMIN_ID = UUID.fromString("0a8b17d8-4497-48aa-b8a8-c30a8b869a62");

    private JdbcTemplate adminJdbc;
    private JdbcTemplate jdbc;
    private JdbcClient client;
    private TransactionTemplate transaction;
    private BCryptPasswordEncoder encoder;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        adminJdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_bootstrap_password_" + UUID.randomUUID().toString().replace("-", "");
        adminJdbc.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        client = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        encoder = new BCryptPasswordEncoder(4);
        createTestSchema();
    }

    @AfterEach
    void tearDown() {
        if (adminJdbc != null && schema != null)
            adminJdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void currentLoginChallengeRequiresAReasonOnlyForPasswordChangeState() {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,password_hash) VALUES(?,?,?,?)",
                userId,
                "migration-user",
                "Migration User",
                "unused-hash");
        UUID temporaryChallenge = UUID.randomUUID();
        UUID mfaChallenge = UUID.randomUUID();
        insertLoginChallenge(
                temporaryChallenge, userId, "PASSWORD_CHANGE_REQUIRED", "TEMPORARY_RESET");
        insertLoginChallenge(mfaChallenge, userId, "MFA_REQUIRED", null);

        assertEquals(
                "TEMPORARY_RESET",
                jdbc.queryForObject(
                        "SELECT password_change_reason FROM platform_login_challenge WHERE id=?",
                        String.class,
                        temporaryChallenge));
        assertNull(
                jdbc.queryForObject(
                        "SELECT password_change_reason FROM platform_login_challenge WHERE id=?",
                        String.class,
                        mfaChallenge));
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO platform_login_challenge(id,user_id,provider_id,session_hash,expires_at,state) VALUES(?,?,'test-provider',?,CURRENT_TIMESTAMP+interval '10 minutes','PASSWORD_CHANGE_REQUIRED')",
                                UUID.randomUUID(),
                                userId,
                                "0".repeat(64)));
    }

    @Test
    void freshBootstrapCreatesAdminAndDurableRequirementInOneTransaction() throws Exception {
        runBootstrap(BOOTSTRAP_PASSWORD);

        UUID revision =
                jdbc.queryForObject(
                        "SELECT primary_credential_revision FROM platform_user WHERE username='admin'",
                        UUID.class);
        assertTrue(
                encoder.matches(
                        BOOTSTRAP_PASSWORD,
                        jdbc.queryForObject(
                                "SELECT password_hash FROM platform_user WHERE username='admin'",
                                String.class)));
        assertEquals(
                revision,
                jdbc.queryForObject(
                        "SELECT primary_credential_revision FROM platform_bootstrap_password_change_requirement WHERE user_id=(SELECT id FROM platform_user WHERE username='admin')",
                        UUID.class));
        assertNull(
                jdbc.queryForObject(
                        "SELECT completed_at FROM platform_bootstrap_password_change_requirement WHERE user_id=(SELECT id FROM platform_user WHERE username='admin')",
                        java.time.OffsetDateTime.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_authority WHERE user_id=(SELECT id FROM platform_user WHERE username='admin')",
                        Integer.class));
        assertEquals(
                BootstrapAdministratorRoleAdapter.ADMINISTRATOR_ROLE_ID,
                jdbc.queryForObject(
                        "SELECT role_id FROM platform_user_role WHERE user_id=(SELECT id FROM platform_user WHERE username='admin')",
                        UUID.class));
    }

    @Test
    void failedRequirementWriteRollsBackBootstrapAdministratorAndGrants() throws Exception {
        jdbc.execute(
                "CREATE FUNCTION reject_bootstrap_requirement() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test marker failure'; END $$");
        jdbc.execute(
                "CREATE TRIGGER reject_bootstrap_requirement BEFORE INSERT ON platform_bootstrap_password_change_requirement FOR EACH ROW EXECUTE FUNCTION reject_bootstrap_requirement()");

        assertThrows(RuntimeException.class, () -> runBootstrap(BOOTSTRAP_PASSWORD));

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM platform_user", Integer.class));
        assertEquals(
                0, jdbc.queryForObject("SELECT count(*) FROM platform_authority", Integer.class));
        assertEquals(
                0, jdbc.queryForObject("SELECT count(*) FROM platform_user_role", Integer.class));
    }

    @Test
    void customizedExistingAdminIsNotChanged() throws Exception {
        UUID originalRevision = insertAdmin("Custom-password-9841!");
        insertAuthority(ADMIN_ID, "platform:custom:retained");
        jdbc.update("INSERT INTO spring_session(principal_name) VALUES('admin')");

        runBootstrap(BOOTSTRAP_PASSWORD);

        assertEquals(
                originalRevision,
                jdbc.queryForObject(
                        "SELECT primary_credential_revision FROM platform_user WHERE username='admin'",
                        UUID.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_bootstrap_password_change_requirement",
                        Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_authority WHERE user_id=? AND authority='platform:custom:retained'",
                        Integer.class,
                        ADMIN_ID));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM platform_user_role", Integer.class));
    }

    @Test
    void restartDoesNotRestoreRemovedBootstrapRoleOrRewriteDirectGrants() throws Exception {
        runBootstrap(BOOTSTRAP_PASSWORD);
        UUID adminId =
                jdbc.queryForObject(
                        "SELECT id FROM platform_user WHERE username='admin'", UUID.class);
        jdbc.update(
                "DELETE FROM platform_user_role WHERE user_id=(SELECT id FROM platform_user WHERE username='admin')");
        insertAuthority(adminId, "platform:custom:retained");
        insertAuthority(adminId, "platform:users:access");

        runBootstrap(BOOTSTRAP_PASSWORD);

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM platform_user_role", Integer.class));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_authority WHERE user_id=?",
                        Integer.class,
                        adminId));
    }

    @Test
    void restartDoesNotReenableAnAdministratorRoleDisabledManually() throws Exception {
        runBootstrap(BOOTSTRAP_PASSWORD);
        UUID adminId =
                jdbc.queryForObject(
                        "SELECT id FROM platform_user WHERE username='admin'", UUID.class);
        jdbc.update(
                "UPDATE platform_role SET enabled=false WHERE id=?",
                BootstrapAdministratorRoleAdapter.ADMINISTRATOR_ROLE_ID);

        runBootstrap(BOOTSTRAP_PASSWORD);

        assertFalse(
                jdbc.queryForObject(
                        "SELECT enabled FROM platform_role WHERE id=?",
                        Boolean.class,
                        BootstrapAdministratorRoleAdapter.ADMINISTRATOR_ROLE_ID));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_user_role assigned JOIN platform_role role ON role.id=assigned.role_id JOIN platform_role_authority role_grant ON role_grant.role_id=role.id WHERE assigned.user_id=? AND role.enabled AND role_grant.authority='platform:users:access'",
                        Integer.class,
                        adminId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM platform_authority", Integer.class));
    }

    @Test
    void matchingLegacyBootstrapAdminIsBoundToNewEpochAndOldProofsAreRevoked() throws Exception {
        UUID originalRevision = insertAdmin(BOOTSTRAP_PASSWORD);
        jdbc.update("INSERT INTO spring_session(principal_name) VALUES('admin')");
        insertLoginChallenge(UUID.randomUUID(), ADMIN_ID, "MFA_REQUIRED", null);

        runBootstrap(BOOTSTRAP_PASSWORD);

        UUID newRevision =
                jdbc.queryForObject(
                        "SELECT primary_credential_revision FROM platform_user WHERE username='admin'",
                        UUID.class);
        assertNotEquals(originalRevision, newRevision);
        assertEquals(
                newRevision,
                jdbc.queryForObject(
                        "SELECT primary_credential_revision FROM platform_bootstrap_password_change_requirement WHERE user_id=?",
                        UUID.class,
                        ADMIN_ID));
        assertNull(
                jdbc.queryForObject(
                        "SELECT completed_at FROM platform_bootstrap_password_change_requirement WHERE user_id=?",
                        java.time.OffsetDateTime.class,
                        ADMIN_ID));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM platform_user_role", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_login_challenge", Integer.class));
    }

    @Test
    void disabledLegacyAdminStillReceivesRequirementBeforeItCanBeReenabled() throws Exception {
        insertAdmin(BOOTSTRAP_PASSWORD);
        jdbc.update("UPDATE platform_user SET enabled=false WHERE id=?", ADMIN_ID);

        runBootstrap(BOOTSTRAP_PASSWORD);

        assertNull(
                jdbc.queryForObject(
                        "SELECT completed_at FROM platform_bootstrap_password_change_requirement WHERE user_id=?",
                        java.time.OffsetDateTime.class,
                        ADMIN_ID));
        assertFalse(
                jdbc.queryForObject(
                        "SELECT enabled FROM platform_user WHERE id=?", Boolean.class, ADMIN_ID));
    }

    @Test
    void retainedPasswordChangeAuditCreatesCompletedCompatibilityMarker() throws Exception {
        UUID originalRevision = insertAdmin(BOOTSTRAP_PASSWORD);
        jdbc.update(
                "INSERT INTO platform_audit(actor,action,target_id) VALUES('admin','account.passwordChanged',?)",
                ADMIN_ID);
        jdbc.update("INSERT INTO spring_session(principal_name) VALUES('admin')");

        runBootstrap(BOOTSTRAP_PASSWORD);

        assertEquals(
                originalRevision,
                jdbc.queryForObject(
                        "SELECT primary_credential_revision FROM platform_user WHERE username='admin'",
                        UUID.class));
        assertNotNull(
                jdbc.queryForObject(
                        "SELECT completed_at FROM platform_bootstrap_password_change_requirement WHERE user_id=?",
                        java.time.OffsetDateTime.class,
                        ADMIN_ID));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM platform_user_role", Integer.class));
    }

    @Test
    void completedMarkerSurvivesRestartAndReuseOfBootstrapValue() throws Exception {
        runBootstrap(BOOTSTRAP_PASSWORD);
        UUID adminId =
                jdbc.queryForObject(
                        "SELECT id FROM platform_user WHERE username='admin'", UUID.class);
        jdbc.update(
                "UPDATE platform_user SET password_hash=?,primary_credential_revision=gen_random_uuid() WHERE id=?",
                encoder.encode("Admin!Changed8274"),
                adminId);
        IdentityConfiguration.completeBootstrapRequirement(client, adminId);
        jdbc.update(
                "UPDATE platform_user SET password_hash=? WHERE id=?",
                encoder.encode(BOOTSTRAP_PASSWORD),
                adminId);
        UUID completedRevision =
                jdbc.queryForObject(
                        "SELECT primary_credential_revision FROM platform_user WHERE id=?",
                        UUID.class,
                        adminId);

        runBootstrap(BOOTSTRAP_PASSWORD);

        assertEquals(
                completedRevision,
                jdbc.queryForObject(
                        "SELECT primary_credential_revision FROM platform_user WHERE id=?",
                        UUID.class,
                        adminId));
        assertNotNull(
                jdbc.queryForObject(
                        "SELECT completed_at FROM platform_bootstrap_password_change_requirement WHERE user_id=?",
                        java.time.OffsetDateTime.class,
                        adminId));
    }

    @Test
    void completionTimestampNeverPredatesMarkerCreation() throws Exception {
        runBootstrap(BOOTSTRAP_PASSWORD);
        UUID adminId =
                jdbc.queryForObject(
                        "SELECT id FROM platform_user WHERE username='admin'", UUID.class);
        jdbc.update(
                "UPDATE platform_bootstrap_password_change_requirement SET created_at=CURRENT_TIMESTAMP+interval '1 day' WHERE user_id=?",
                adminId);

        IdentityConfiguration.completeBootstrapRequirement(client, adminId);

        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_bootstrap_password_change_requirement WHERE user_id=? AND completed_at<created_at",
                        Integer.class,
                        adminId));
    }

    private UUID insertAdmin(String password) {
        UUID revision = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,password_hash,primary_credential_revision) VALUES(?, 'admin', '管理员', ?, ?)",
                ADMIN_ID,
                encoder.encode(password),
                revision);
        return revision;
    }

    private void runBootstrap(String password) throws Exception {
        var configuration = new IdentityConfiguration();
        configuration
                .bootstrap(
                        client,
                        encoder,
                        transaction,
                        new IdentityAuthenticationPolicyWriteLock(client),
                        new BootstrapAdministratorRoleAdapter(client),
                        password)
                .run(new DefaultApplicationArguments(new String[0]));
    }

    private void insertLoginChallenge(UUID id, UUID userId, String state, String reason) {
        jdbc.update(
                "INSERT INTO platform_login_challenge(id,user_id,provider_id,session_hash,expires_at,state,password_change_reason) VALUES(?,?,'test-provider',?,CURRENT_TIMESTAMP+interval '10 minutes',?,?)",
                id,
                userId,
                "0".repeat(64),
                state,
                reason);
    }

    private void createTestSchema() {
        jdbc.execute(
                "CREATE TABLE platform_user(id uuid PRIMARY KEY,username text UNIQUE NOT NULL,display_name text NOT NULL,password_hash text NOT NULL,enabled boolean NOT NULL DEFAULT true,deleted_at timestamptz,primary_credential_revision uuid NOT NULL DEFAULT gen_random_uuid())");
        jdbc.execute(TestSchema.table("platform_authority"));
        jdbc.execute(TestSchema.table("platform_role"));
        jdbc.execute(TestSchema.table("platform_role_authority"));
        jdbc.execute(TestSchema.table("platform_user_role"));
        TestSchema.seeds("platform_role").forEach(jdbc::execute);
        TestSchema.seeds("platform_role_authority").forEach(jdbc::execute);
        jdbc.execute(
                "CREATE TABLE platform_authentication_policy_lock(id integer PRIMARY KEY CHECK(id=1))");
        jdbc.update("INSERT INTO platform_authentication_policy_lock VALUES(1)");
        jdbc.execute(
                "CREATE TABLE platform_audit(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),actor text NOT NULL,action text NOT NULL,target_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE platform_password_change_requirement(user_id uuid PRIMARY KEY)");
        jdbc.execute(TestSchema.table("platform_login_challenge"));
        jdbc.execute(TestSchema.table("platform_bootstrap_password_change_requirement"));
        jdbc.execute("CREATE TABLE spring_session(principal_name text NOT NULL)");
    }

    private void insertAuthority(UUID userId, String authority) {
        jdbc.update(
                "INSERT INTO platform_authority(user_id,authority) VALUES(?,?)",
                userId,
                authority);
    }
}
