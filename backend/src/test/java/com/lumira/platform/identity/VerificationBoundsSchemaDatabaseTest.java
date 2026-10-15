package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class VerificationBoundsSchemaDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;

    @BeforeEach
    void setUp() {
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
        for (String table :
                java.util.List.of(
                        "platform_account_email",
                        "platform_account_phone",
                        "platform_password_reset",
                        "platform_passkey_challenge")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void persistedCountersAcceptThePolicyCeilingsAndRejectHigherValues() {
        jdbc.update(
                "INSERT INTO platform_account_email(user_id,failures,request_count) VALUES(?,20,10000)",
                java.util.UUID.randomUUID());
        jdbc.update(
                "INSERT INTO platform_account_phone(user_id,failures,request_count) VALUES(?,20,10000)",
                java.util.UUID.randomUUID());
        jdbc.update(
                "INSERT INTO platform_password_reset(user_id,requested_at,request_day,failures,request_count) VALUES(?,CURRENT_TIMESTAMP,CURRENT_DATE,20,10000)",
                java.util.UUID.randomUUID());
        jdbc.update(
                "INSERT INTO platform_account_email(user_id,failures,request_count) VALUES(?,0,0)",
                java.util.UUID.randomUUID());
        jdbc.update(
                "INSERT INTO platform_account_phone(user_id,failures,request_count) VALUES(?,0,0)",
                java.util.UUID.randomUUID());
        jdbc.update(
                "INSERT INTO platform_password_reset(user_id,requested_at,request_day,failures,request_count) VALUES(?,CURRENT_TIMESTAMP,CURRENT_DATE,0,1)",
                java.util.UUID.randomUUID());

        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO platform_account_email(user_id,failures,request_count) VALUES(?,21,0)",
                                java.util.UUID.randomUUID()));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO platform_account_email(user_id,failures,request_count) VALUES(?,0,10001)",
                                java.util.UUID.randomUUID()));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO platform_account_phone(user_id,failures,request_count) VALUES(?,0,10001)",
                                java.util.UUID.randomUUID()));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO platform_password_reset(user_id,requested_at,request_day,failures,request_count) VALUES(?,CURRENT_TIMESTAMP,CURRENT_DATE,21,1)",
                                java.util.UUID.randomUUID()));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO platform_password_reset(user_id,requested_at,request_day,failures,request_count) VALUES(?,CURRENT_TIMESTAMP,CURRENT_DATE,0,0)",
                                java.util.UUID.randomUUID()));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO platform_password_reset(user_id,requested_at,request_day,failures,request_count) VALUES(?,CURRENT_TIMESTAMP,CURRENT_DATE,0,10001)",
                                java.util.UUID.randomUUID()));
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
