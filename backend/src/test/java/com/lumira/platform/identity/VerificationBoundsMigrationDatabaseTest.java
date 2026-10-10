package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class VerificationBoundsMigrationDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_verify_bounds_" + java.util.UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                "CREATE TABLE platform_account_email(failures integer NOT NULL DEFAULT 0,request_count integer NOT NULL DEFAULT 0,CONSTRAINT platform_account_email_failures_check CHECK(failures BETWEEN 0 AND 5),CONSTRAINT platform_account_email_request_count_check CHECK(request_count BETWEEN 0 AND 10))");
        jdbc.execute(
                "CREATE TABLE platform_account_phone(failures integer NOT NULL DEFAULT 0,request_count integer NOT NULL DEFAULT 0,CONSTRAINT platform_account_phone_failures_check CHECK(failures BETWEEN 0 AND 5),CONSTRAINT platform_account_phone_request_count_check CHECK(request_count BETWEEN 0 AND 10))");
        jdbc.execute(
                "CREATE TABLE platform_password_reset(failures integer NOT NULL DEFAULT 0,request_count integer NOT NULL,CONSTRAINT platform_password_reset_failures_check CHECK(failures BETWEEN 0 AND 5),CONSTRAINT platform_password_reset_request_count_check CHECK(request_count BETWEEN 1 AND 10))");
        jdbc.execute(
                "CREATE TABLE platform_passkey_challenge(id uuid PRIMARY KEY,session_id text NOT NULL,kind text NOT NULL,expires_at timestamptz NOT NULL)");
        try (var migration =
                VerificationBoundsMigrationDatabaseTest.class.getResourceAsStream(
                        "/db/migration/V45__identity_verification_count_bounds_and_passkey_limits.sql")) {
            if (migration == null)
                throw new IllegalStateException(
                        "Current V45 migration is missing from the classpath");
            jdbc.execute(new String(migration.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void persistedCountersAcceptThePolicyCeilingsAndRejectHigherValues() {
        jdbc.update("INSERT INTO platform_account_email(failures,request_count) VALUES(20,10000)");
        jdbc.update("INSERT INTO platform_account_phone(failures,request_count) VALUES(20,10000)");
        jdbc.update("INSERT INTO platform_password_reset(failures,request_count) VALUES(20,10000)");
        jdbc.update("INSERT INTO platform_account_email(failures,request_count) VALUES(0,0)");
        jdbc.update("INSERT INTO platform_account_phone(failures,request_count) VALUES(0,0)");
        jdbc.update("INSERT INTO platform_password_reset(failures,request_count) VALUES(0,1)");

        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO platform_account_email(failures,request_count) VALUES(21,0)"));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO platform_account_email(failures,request_count) VALUES(0,10001)"));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO platform_account_phone(failures,request_count) VALUES(0,10001)"));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO platform_password_reset(failures,request_count) VALUES(21,1)"));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO platform_password_reset(failures,request_count) VALUES(0,0)"));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO platform_password_reset(failures,request_count) VALUES(0,10001)"));
    }

    @Test
    void passkeyIndexesCoverSessionKindCapacityAndCleanupSelection() {
        assertTrue(indexExists("platform_passkey_challenge_session_kind"));
        assertTrue(indexExists("platform_passkey_challenge_cleanup"));
    }

    private boolean indexExists(String name) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, name));
    }
}
