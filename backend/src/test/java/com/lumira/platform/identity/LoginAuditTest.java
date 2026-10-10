package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestMigration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class LoginAuditTest {
    JdbcTemplate admin, jdbc;
    String schema;
    LoginAudit audit;

    @BeforeEach
    void setup() throws Exception {
        var url = System.getenv("LUMIRA_TEST_DB_URL");
        var user = System.getenv("LUMIRA_TEST_DB_USER");
        var password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_login_audit_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(ds);
        audit = new LoginAudit(JdbcClient.create(ds));
        jdbc.execute("CREATE TABLE platform_user(id UUID PRIMARY KEY,username TEXT UNIQUE)");
        jdbc.execute("CREATE TABLE platform_audit(id UUID,actor TEXT,action TEXT,target_id UUID)");
        jdbc.execute(TestMigration.read("V29__audit_client_context.sql"));
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void boundsClientContextAndKeepsHistoricalRowsNullable() {
        jdbc.update("INSERT INTO platform_user VALUES(?,'owner')", UUID.randomUUID());
        audit.record("owner", true, "2001:db8::1", " Agent\r\n\u0000\u202e" + "x".repeat(400));
        assertEquals(
                "2001:db8:0:0:0:0:0:1",
                jdbc.queryForObject("SELECT client_address FROM platform_audit", String.class));
        var agent = jdbc.queryForObject("SELECT user_agent FROM platform_audit", String.class);
        assertTrue(agent.startsWith("Agent"));
        assertTrue(agent.length() <= 256);
        assertFalse(agent.contains("\n"));
        assertFalse(agent.contains("\u202e"));
        audit.record("owner", false, null, null);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_audit WHERE user_agent IS NULL AND client_address IS NULL",
                        Integer.class));
    }

    @Test
    void resolvesIdentityWithoutPersistingUnknownInput() {
        var owner = UUID.randomUUID();
        jdbc.update("INSERT INTO platform_user VALUES(?,'owner')", owner);
        audit.record("owner", true, null, null);
        audit.record("owner", false, null, null);
        audit.record("unknown", false, null, null);
        audit.record(null, false, null, null);
        audit.record("x".repeat(101), false, null, null);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM platform_audit", Integer.class));
        assertEquals(
                List.of(owner, owner),
                jdbc.queryForList("SELECT target_id FROM platform_audit", UUID.class));
        assertEquals(
                Set.of("account.loginSucceeded", "account.loginFailed"),
                new HashSet<>(
                        jdbc.queryForList("SELECT action FROM platform_audit", String.class)));
        jdbc.update("DELETE FROM platform_user");
        var replacement = UUID.randomUUID();
        jdbc.update("INSERT INTO platform_user VALUES(?,'owner')", replacement);
        audit.record("owner", true, null, null);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_audit WHERE target_id=?",
                        Integer.class,
                        replacement));
    }

    @Test
    void recordsOnlyAllowlistedAccountBoundRejectionActions() {
        var owner = UUID.randomUUID();
        jdbc.update("INSERT INTO platform_user VALUES(?,'owner')", owner);
        audit.record("owner", "account.loginRateLimited", "192.0.2.1", "browser");
        audit.record("owner", "account.loginRejected", "192.0.2.1", "browser");
        audit.record("owner", "account.passwordReset", "192.0.2.1", "browser");
        audit.record("unknown", "account.loginRejected", "192.0.2.1", "browser");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM platform_audit", Integer.class));
        assertEquals(
                Set.of("account.loginRateLimited", "account.loginRejected"),
                new HashSet<>(
                        jdbc.queryForList("SELECT action FROM platform_audit", String.class)));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_audit WHERE target_id=? AND client_address='192.0.2.1' AND user_agent='browser'",
                        Integer.class,
                        owner));
    }
}
