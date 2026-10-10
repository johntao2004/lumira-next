package com.lumira.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class MfaOfflineRecoveryCommandDatabaseTest {
    private static final UUID USER_ID = UUID.fromString("b7d0c835-c2e8-46b8-9519-9a409890a84b");
    private static final UUID OLD_FACTOR = UUID.fromString("43f8b106-6f86-4a86-95d7-783be2e9813f");

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private String url;
    private String user;
    private String password;
    private String keyRing;
    private Path tempDirectory;

    @BeforeEach
    void setUp() throws Exception {
        String baseUrl = System.getenv("LUMIRA_TEST_DB_URL");
        user = System.getenv("LUMIRA_TEST_DB_USER");
        password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(baseUrl, user, password));
        schema = "qa_offline_mfa_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        keyRing = "primary=" + Base64.getEncoder().encodeToString(new byte[32]);
        tempDirectory = Files.createTempDirectory("lumira-mfa-recovery-");
        createSchema();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDirectory != null) {
            try (var paths = Files.list(tempDirectory)) {
                paths.forEach(
                        path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                            }
                        });
            }
            Files.deleteIfExists(tempDirectory);
        }
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void fileDeliveryPreservesSelfRequirementRevokesSessionsAndNeverPrintsTicket()
            throws Exception {
        insertEligibleUser(false, true);
        jdbc.update(
                "INSERT INTO spring_session(session_id,principal_name) VALUES ('session-1','mfa-user')");
        jdbc.update("INSERT INTO platform_login_challenge(user_id) VALUES (?)", USER_ID);
        jdbc.update("INSERT INTO platform_reauthentication_challenge(user_id) VALUES (?)", USER_ID);
        jdbc.update("INSERT INTO platform_passkey_challenge(user_id) VALUES (?)", USER_ID);
        jdbc.update(
                "INSERT INTO platform_public_auth_challenge(user_id,purpose) VALUES (?, 'LOGIN')",
                USER_ID);
        Path outputFile = tempDirectory.resolve("recovery-ticket.txt");
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int status =
                MfaOfflineRecoveryCommand.run(
                        args(outputFile),
                        environment(),
                        new PrintStream(output),
                        new PrintStream(errors),
                        (databaseUrl, databaseUser, databasePassword) ->
                                DriverManager.getConnection(
                                        databaseUrl, databaseUser, databasePassword));

        assertEquals(0, status, errors.toString(StandardCharsets.UTF_8));
        String ticket = Files.readString(outputFile, StandardCharsets.US_ASCII).strip();
        assertTrue(ticket.matches("[0-9a-f]{64}"));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains(ticket));
        assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(outputFile));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("secret="));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_second_factor_requirement WHERE user_id=? AND self_enabled AND recovery_pending",
                        Integer.class,
                        USER_ID));
        UUID newFactor =
                jdbc.queryForObject(
                        "SELECT factor_credential_revision FROM platform_user WHERE id=?",
                        UUID.class,
                        USER_ID);
        assertNotEquals(OLD_FACTOR, newFactor);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_mfa_recovery_ticket WHERE user_id=? AND factor_credential_revision=? AND expires_at>CURRENT_TIMESTAMP",
                        Integer.class,
                        USER_ID,
                        newFactor));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM plugin_mfa_authenticator WHERE user_id=?",
                        Integer.class,
                        USER_ID));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM spring_session WHERE principal_name='mfa-user'",
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_mfa_offline_recovery_audit WHERE target_user_id=? AND delivery_method='FILE'",
                        Integer.class,
                        USER_ID));
    }

    @Test
    void verifiedEmailUsesEncryptedQueueAndExplicitFileFallbackIsRejected() {
        insertEligibleUser(true, true);
        Path outputFile = tempDirectory.resolve("must-not-exist.txt");
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int rejectedFile =
                MfaOfflineRecoveryCommand.run(
                        args(outputFile),
                        environment(),
                        new PrintStream(output),
                        new PrintStream(errors),
                        (databaseUrl, databaseUser, databasePassword) ->
                                DriverManager.getConnection(
                                        databaseUrl, databaseUser, databasePassword));

        assertEquals(4, rejectedFile);
        assertTrue(
                errors.toString(StandardCharsets.UTF_8)
                        .contains("offlineMfaRecoveryFileDeliveryNotAllowed"));
        assertFalse(Files.exists(outputFile));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_mfa_offline_recovery_audit", Integer.class));

        output.reset();
        errors.reset();
        int queued =
                MfaOfflineRecoveryCommand.run(
                        args(),
                        environment(),
                        new PrintStream(output),
                        new PrintStream(errors),
                        (databaseUrl, databaseUser, databasePassword) ->
                                DriverManager.getConnection(
                                        databaseUrl, databaseUser, databasePassword));
        assertEquals(0, queued, errors.toString(StandardCharsets.UTF_8));
        String encrypted =
                jdbc.queryForObject("SELECT payload FROM platform_mail_delivery", String.class);
        assertTrue(encrypted.startsWith("v1:primary:"));
        assertFalse(encrypted.matches("(?s).*\\b[0-9a-f]{64}\\b.*"));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_mfa_offline_recovery_audit WHERE delivery_method='MAIL'",
                        Integer.class));
        assertFalse(output.toString(StandardCharsets.UTF_8).matches("(?s).*\\b[0-9a-f]{64}\\b.*"));
    }

    @Test
    void ineligibleAccountAndExistingFileFailBeforeAnyRecoveryMutation() throws Exception {
        jdbc.update(
                """
                INSERT INTO platform_user(id,username,enabled,factor_credential_revision)
                VALUES (?, 'mfa-user', true, ?)
                """,
                USER_ID,
                OLD_FACTOR);
        Path existing = tempDirectory.resolve("existing.txt");
        Files.writeString(existing, "do not replace", StandardCharsets.US_ASCII);
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int status =
                MfaOfflineRecoveryCommand.run(
                        args(existing),
                        environment(),
                        new PrintStream(output),
                        new PrintStream(errors),
                        (databaseUrl, databaseUser, databasePassword) ->
                                DriverManager.getConnection(
                                        databaseUrl, databaseUser, databasePassword));

        assertEquals(2, status);
        assertEquals("do not replace", Files.readString(existing, StandardCharsets.US_ASCII));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_mfa_offline_recovery_audit", Integer.class));
        assertEquals(
                OLD_FACTOR,
                jdbc.queryForObject(
                        "SELECT factor_credential_revision FROM platform_user WHERE id=?",
                        UUID.class,
                        USER_ID));
    }

    private void insertEligibleUser(boolean verifiedEmail, boolean selfEnabled) {
        jdbc.update(
                """
                INSERT INTO platform_user(id,username,enabled,factor_credential_revision)
                VALUES (?, 'mfa-user', true, ?)
                """,
                USER_ID,
                OLD_FACTOR);
        jdbc.update(
                """
                INSERT INTO platform_second_factor_requirement(user_id,provider_id,self_enabled,recovery_pending)
                VALUES (?, 'mfa', ?, false)
                """,
                USER_ID,
                selfEnabled);
        jdbc.update(
                """
                INSERT INTO plugin_mfa_authenticator(user_id,confirmed)
                VALUES (?, true)
                """,
                USER_ID);
        if (verifiedEmail)
            jdbc.update(
                    "INSERT INTO platform_account_email(user_id,email,verified_at) VALUES (?, 'mfa@example.test', CURRENT_TIMESTAMP)",
                    USER_ID);
    }

    private String[] args(Path file) {
        return new String[] {
            "--offline-mfa-recovery",
            "--username",
            "mfa-user",
            "--operator",
            "deployment-operator",
            "--reason",
            "Controlled offline recovery test",
            "--ticket-file",
            file.toString()
        };
    }

    private String[] args() {
        return new String[] {
            "--offline-mfa-recovery",
            "--username",
            "mfa-user",
            "--operator",
            "deployment-operator",
            "--reason",
            "Controlled offline recovery test"
        };
    }

    private Map<String, String> environment() {
        Map<String, String> values = new HashMap<>();
        values.put("LUMIRA_DB_URL", url);
        values.put("LUMIRA_DB_USER", user);
        values.put("LUMIRA_DB_PASSWORD", password);
        values.put("LUMIRA_SECRET_KEYS", keyRing);
        values.put("LUMIRA_SECRET_ACTIVE_KEY", "primary");
        return values;
    }

    private void createSchema() {
        jdbc.execute(
                "CREATE TABLE platform_authentication_policy_lock(id smallint PRIMARY KEY CHECK(id=1))");
        jdbc.update("INSERT INTO platform_authentication_policy_lock VALUES (1)");
        jdbc.execute(
                "CREATE TABLE platform_global_authentication_policy(id smallint PRIMARY KEY, mfa_required boolean NOT NULL)");
        jdbc.update("INSERT INTO platform_global_authentication_policy VALUES (1,false)");
        jdbc.execute("CREATE TABLE platform_role(id uuid PRIMARY KEY, enabled boolean NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_user_role(user_id uuid NOT NULL, role_id uuid NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_role_authentication_policy(role_id uuid PRIMARY KEY, mfa_mode varchar(16) NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_user(id uuid PRIMARY KEY, username varchar(100) UNIQUE NOT NULL, enabled boolean NOT NULL, deleted_at timestamptz, factor_credential_revision uuid NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_second_factor_requirement(user_id uuid PRIMARY KEY, provider_id varchar(64) NOT NULL, self_enabled boolean NOT NULL DEFAULT false, recovery_pending boolean NOT NULL DEFAULT false, requirement_version uuid NOT NULL DEFAULT gen_random_uuid())");
        jdbc.execute(
                "CREATE TABLE platform_account_email(user_id uuid PRIMARY KEY, email text, verified_at timestamptz)");
        jdbc.execute(
                "CREATE TABLE plugin_mfa_authenticator(user_id uuid PRIMARY KEY, confirmed boolean NOT NULL)");
        jdbc.execute(
                "CREATE TABLE plugin_mfa_recovery(user_id uuid NOT NULL REFERENCES plugin_mfa_authenticator(user_id) ON DELETE CASCADE, digest char(64) NOT NULL)");
        jdbc.execute("CREATE TABLE plugin_mfa_replacement(user_id uuid PRIMARY KEY)");
        jdbc.execute(
                "CREATE TABLE platform_mfa_recovery_ticket(id uuid PRIMARY KEY, user_id uuid NOT NULL, token_digest char(64) NOT NULL, factor_credential_revision uuid NOT NULL, expires_at timestamptz NOT NULL, consumed_at timestamptz, revoked_at timestamptz)");
        jdbc.execute(
                "CREATE UNIQUE INDEX platform_mfa_recovery_ticket_active_user_idx ON platform_mfa_recovery_ticket(user_id) WHERE consumed_at IS NULL AND revoked_at IS NULL");
        jdbc.execute(
                "CREATE TABLE platform_mfa_recovery_request_rate(user_id uuid PRIMARY KEY, failures integer NOT NULL DEFAULT 0, failure_day date)");
        jdbc.execute(
                "CREATE TABLE platform_mfa_offline_recovery_audit(event_id uuid PRIMARY KEY, target_user_id uuid NOT NULL, target_username varchar(100) NOT NULL, operator varchar(100) NOT NULL, reason varchar(500) NOT NULL, delivery_method varchar(16) NOT NULL, ticket_expires_at timestamptz NOT NULL)");
        jdbc.execute("CREATE TABLE platform_login_challenge(user_id uuid NOT NULL)");
        jdbc.execute("CREATE TABLE platform_reauthentication_challenge(user_id uuid NOT NULL)");
        jdbc.execute("CREATE TABLE platform_passkey_challenge(user_id uuid)");
        jdbc.execute(
                "CREATE TABLE platform_public_auth_challenge(user_id uuid, purpose varchar(24) NOT NULL)");
        jdbc.execute(
                "CREATE TABLE spring_session(session_id varchar(36) PRIMARY KEY, principal_name varchar(100))");
        jdbc.execute(
                "CREATE TABLE platform_mail_delivery(id uuid PRIMARY KEY, payload text NOT NULL, expires_at timestamptz NOT NULL)");
    }
}
