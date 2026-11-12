package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.AuditPort;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses real baseline constraints and PostgreSQL transactions to exercise admission overlap. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class RolePreviewAdmissionConcurrencyDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private RolePreviewContextService service;
    private IdentityAuthenticationPolicyWriteLock policyLock;
    private String schema;
    private final UUID actor = UUID.randomUUID();
    private final String primaryId = UUID.randomUUID().toString();
    private final String sessionId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_preview_admission_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        for (String table :
                new String[] {
                    "platform_authentication_policy_lock", "spring_session",
                    "platform_role_preview_context", "platform_authority",
                    "platform_permission_retired_authority", "platform_user_role",
                    "platform_role", "platform_role_authority"
                }) jdbc.execute(TestSchema.table(table));
        jdbc.execute(TestSchema.view("platform_global_authority"));
        jdbc.update("INSERT INTO platform_authentication_policy_lock(id) VALUES (1)");
        jdbc.update(
                """
                INSERT INTO spring_session(primary_id,session_id,creation_time,last_access_time,
                    max_inactive_interval,expiry_time,principal_name)
                VALUES (?,?,0,0,1800,?,'actor')
                """,
                primaryId,
                sessionId,
                System.currentTimeMillis() + 60_000);
        jdbc.update(
                """
                INSERT INTO platform_role_preview_context(session_primary_id,actor_id,revision,state)
                VALUES (?,?,?,'NONE')
                """,
                primaryId,
                actor,
                UUID.randomUUID());
        var client = JdbcClient.create(source);
        policyLock = new IdentityAuthenticationPolicyWriteLock(client);
        service = new RolePreviewContextService(client, mock(AuditPort.class), null, policyLock);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void normalRequestsOnOneSessionCanOverlapWithoutChangingRevision() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () ->
                                    transaction.execute(
                                            status -> {
                                                var context = resolve();
                                                entered.countDown();
                                                await(release);
                                                return context;
                                            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var second =
                        pool.submit(
                                () ->
                                        transaction.execute(
                                                status -> {
                                                    jdbc.execute("SET LOCAL lock_timeout='1s'");
                                                    return resolve();
                                                }));
                var result = second.get(5, TimeUnit.SECONDS);
                assertFalse(result.previewActive());
                assertFalse(result.previewBlocked());
                release.countDown();
                assertEquals(first.get(5, TimeUnit.SECONDS).revision(), result.revision());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void normalAdmissionStillExcludesSessionTransitionsAndGlobalPolicyWriters() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reader =
                    pool.submit(
                            () ->
                                    transaction.execute(
                                            status -> {
                                                resolve();
                                                entered.countDown();
                                                await(release);
                                                return true;
                                            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertLockUnavailable(
                        "SELECT state FROM platform_role_preview_context FOR UPDATE NOWAIT");
                assertLockUnavailable(
                        "SELECT id FROM platform_authentication_policy_lock FOR UPDATE NOWAIT");
            } finally {
                release.countDown();
            }
            assertTrue(reader.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void blockedContextRetainsExclusiveAdmission() throws Exception {
        blockContext();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reader =
                    pool.submit(
                            () ->
                                    transaction.execute(
                                            status -> {
                                                assertTrue(resolve().previewBlocked());
                                                entered.countDown();
                                                await(release);
                                                return true;
                                            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertLockUnavailable(
                        "SELECT state FROM platform_role_preview_context FOR SHARE NOWAIT");
            } finally {
                release.countDown();
            }
            assertTrue(reader.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void readerWaitingForTransitionUsesCommittedBlockedState() throws Exception {
        var changed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var readerPid = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer =
                    pool.submit(
                            () ->
                                    transaction.execute(
                                            status -> {
                                                policyLock.lockForAuthentication();
                                                blockContext();
                                                changed.countDown();
                                                await(release);
                                                return true;
                                            }));
            try {
                assertTrue(changed.await(5, TimeUnit.SECONDS));
                var reader =
                        pool.submit(
                                () ->
                                        transaction.execute(
                                                status -> {
                                                    jdbc.execute("SET LOCAL lock_timeout='5s'");
                                                    readerPid.set(
                                                            jdbc.queryForObject(
                                                                    "SELECT pg_backend_pid()",
                                                                    Integer.class));
                                                    return resolve();
                                                }));
                assertTimeoutPreemptively(
                        Duration.ofSeconds(4),
                        () -> {
                            while (readerPid.get() == 0
                                    || !Boolean.TRUE.equals(
                                            admin.queryForObject(
                                                    "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",
                                                    Boolean.class,
                                                    readerPid.get()))) Thread.sleep(10);
                        });
                release.countDown();
                assertTrue(reader.get(5, TimeUnit.SECONDS).previewBlocked());
                assertTrue(writer.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    private com.lumira.platform.identity.api.RequestAuthorizationContext resolve() {
        policyLock.lockForAuthentication();
        var request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession(null, sessionId));
        return service.resolve(request, "actor", actor, Set.of());
    }

    private void blockContext() {
        jdbc.update(
                """
                UPDATE platform_role_preview_context SET state='BLOCKED',role_id=?,
                    expires_at=now()+interval '10 minutes',actor_authority_digest=?,
                    role_authority_digest=?,blocked_reason='roleChanged'
                """,
                UUID.randomUUID(),
                "0".repeat(64),
                "0".repeat(64));
    }

    private void assertLockUnavailable(String sql) {
        var exception =
                assertThrows(
                        DataAccessException.class,
                        () -> transaction.execute(status -> jdbc.queryForList(sql)));
        assertInstanceOf(java.sql.SQLException.class, exception.getMostSpecificCause());
        assertEquals(
                "55P03", ((java.sql.SQLException) exception.getMostSpecificCause()).getSQLState());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
