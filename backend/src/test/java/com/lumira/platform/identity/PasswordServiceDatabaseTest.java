package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class PasswordServiceDatabaseTest {
    private static final String USERNAME = "password-change-user";
    private static final String CURRENT = "Old-strong-password-87!";
    private static final String REPLACEMENT = "New-strong-password-96!";

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private UUID userId;
    private String originalHash;
    private UUID originalRevision;
    private SecurityPolicyPort policy;
    private PasswordService service;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_password_change_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                "CREATE TABLE platform_user(id uuid PRIMARY KEY,username text UNIQUE,password_hash text NOT NULL,enabled boolean NOT NULL,deleted_at timestamptz,primary_credential_revision uuid NOT NULL)");
        jdbc.execute("CREATE TABLE platform_password_change_requirement(user_id uuid PRIMARY KEY)");
        jdbc.execute("CREATE TABLE platform_authentication_policy_lock(id integer PRIMARY KEY)");
        jdbc.execute("INSERT INTO platform_authentication_policy_lock(id) VALUES(1)");
        jdbc.execute(
                "CREATE TABLE spring_session(principal_name text NOT NULL,session_id text NOT NULL)");

        userId = UUID.randomUUID();
        originalRevision = UUID.randomUUID();
        var baseEncoder = new BCryptPasswordEncoder(8);
        originalHash = baseEncoder.encode(CURRENT);
        jdbc.update(
                "INSERT INTO platform_user VALUES(?,?,?,true,NULL,?)",
                userId,
                USERNAME,
                originalHash,
                originalRevision);
        jdbc.update(
                "INSERT INTO spring_session VALUES (?, 'old-a'), (?, 'old-b')", USERNAME, USERNAME);

        policy = mock(SecurityPolicyPort.class);
        when(policy.currentPolicy()).thenReturn(SecurityPolicyPort.Policy.defaults());
        var lock = new IdentityAuthenticationPolicyWriteLock(JdbcClient.create(dataSource));
        var accountSecurity =
                new AccountSecurityService(JdbcClient.create(dataSource), baseEncoder, lock);
        service =
                new PasswordService(
                        JdbcClient.create(dataSource),
                        new TransactionCheckingEncoder(baseEncoder, jdbc, null),
                        mock(AuditPort.class),
                        policy,
                        lock,
                        accountSecurity,
                        new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void matchingAndArgonReplacementHashingRunOutsideTransactionAndCommitShortChange() {
        service.change(USERNAME, CURRENT, REPLACEMENT);

        String changedHash =
                jdbc.queryForObject(
                        "SELECT password_hash FROM platform_user WHERE id=?", String.class, userId);
        assertNotEquals(originalHash, changedHash);
        assertTrue(new BCryptPasswordEncoder(8).matches(REPLACEMENT, changedHash));
        assertNotEquals(
                originalRevision,
                jdbc.queryForObject(
                        "SELECT primary_credential_revision FROM platform_user WHERE id=?",
                        UUID.class,
                        userId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class));
    }

    @Test
    void stalePasswordSnapshotIsRejectedAfterHashWorkWithoutRevokingSessions() {
        service =
                new PasswordService(
                        JdbcClient.create(
                                new DriverManagerDataSource(
                                        System.getenv("LUMIRA_TEST_DB_URL")
                                                + (System.getenv("LUMIRA_TEST_DB_URL").contains("?")
                                                        ? "&"
                                                        : "?")
                                                + "currentSchema="
                                                + schema,
                                        System.getenv("LUMIRA_TEST_DB_USER"),
                                        System.getenv("LUMIRA_TEST_DB_PASSWORD"))),
                        new TransactionCheckingEncoder(
                                new BCryptPasswordEncoder(8), jdbc, "concurrent-password-rotation"),
                        mock(AuditPort.class),
                        policy,
                        new IdentityAuthenticationPolicyWriteLock(
                                JdbcClient.create(
                                        new DriverManagerDataSource(
                                                System.getenv("LUMIRA_TEST_DB_URL")
                                                        + (System.getenv("LUMIRA_TEST_DB_URL")
                                                                        .contains("?")
                                                                ? "&"
                                                                : "?")
                                                        + "currentSchema="
                                                        + schema,
                                                System.getenv("LUMIRA_TEST_DB_USER"),
                                                System.getenv("LUMIRA_TEST_DB_PASSWORD")))),
                        null,
                        new DataSourceTransactionManager(
                                new DriverManagerDataSource(
                                        System.getenv("LUMIRA_TEST_DB_URL")
                                                + (System.getenv("LUMIRA_TEST_DB_URL").contains("?")
                                                        ? "&"
                                                        : "?")
                                                + "currentSchema="
                                                + schema,
                                        System.getenv("LUMIRA_TEST_DB_USER"),
                                        System.getenv("LUMIRA_TEST_DB_PASSWORD"))));

        var failure =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.change(USERNAME, CURRENT, REPLACEMENT));
        assertEquals("passwordChangeExpired", failure.getReason());
        assertEquals(
                "concurrent-password-rotation",
                jdbc.queryForObject(
                        "SELECT password_hash FROM platform_user WHERE id=?",
                        String.class,
                        userId));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class));
    }

    private static final class TransactionCheckingEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate;
        private final JdbcTemplate jdbc;
        private final String replacementDuringEncode;
        private final AtomicBoolean changed = new AtomicBoolean();

        private TransactionCheckingEncoder(
                PasswordEncoder delegate, JdbcTemplate jdbc, String replacementDuringEncode) {
            this.delegate = delegate;
            this.jdbc = jdbc;
            this.replacementDuringEncode = replacementDuringEncode;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if (replacementDuringEncode != null && changed.compareAndSet(false, true))
                jdbc.update(
                        "UPDATE platform_user SET password_hash=? WHERE username=?",
                        replacementDuringEncode,
                        USERNAME);
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return delegate.matches(rawPassword, encodedPassword);
        }
    }
}
