package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.AuthenticationMethodAvailabilityPort;
import com.lumira.platform.identity.api.AuthenticationReachabilityPort;
import com.lumira.platform.settings.GlobalAuthenticationPolicyService;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises the PostgreSQL lock order shared by preview transitions and policy writers. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class RolePreviewPolicyLockOrderDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private JdbcClient client;
    private String schema;
    private UUID actorId;
    private String sessionPrimaryId;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_preview_policy_lock_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        client = JdbcClient.create(dataSource);
        jdbc.execute(
                "CREATE TABLE platform_authentication_policy_lock(id integer PRIMARY KEY CHECK(id=1))");
        jdbc.update("INSERT INTO platform_authentication_policy_lock VALUES (1)");
        jdbc.execute(
                """
                CREATE TABLE platform_global_authentication_policy(
                    id smallint PRIMARY KEY,
                    version bigint NOT NULL,
                    revision uuid NOT NULL,
                    enabled_methods text[] NOT NULL,
                    mfa_required boolean NOT NULL,
                    updated_by text,
                    change_reason text,
                    updated_at timestamptz NOT NULL DEFAULT now())
                """);
        jdbc.execute(
                "CREATE TABLE platform_user(id uuid PRIMARY KEY,enabled boolean NOT NULL,deleted_at timestamptz)");
        jdbc.execute(
                """
                CREATE TABLE platform_role_preview_context(
                    session_primary_id character(36) NOT NULL,
                    actor_id uuid NOT NULL,
                    state text NOT NULL,
                    PRIMARY KEY(session_primary_id,actor_id))
                """);
        actorId = UUID.randomUUID();
        sessionPrimaryId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO platform_role_preview_context VALUES (?,?,'NONE')",
                sessionPrimaryId,
                actorId);
        jdbc.update(
                "INSERT INTO platform_global_authentication_policy(id,version,revision,enabled_methods,mfa_required) VALUES (1,1,?,ARRAY['PASSWORD']::text[],false)",
                UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void previewPolicyShareThenSessionUpdateDoesNotDeadlockWithPolicyWriter() throws Exception {
        var realLock = new IdentityAuthenticationPolicyWriteLock(client);
        var writerAttemptingPolicyLock = new CountDownLatch(1);
        AuthenticationPolicyWriteLock notifyingLock =
                new AuthenticationPolicyWriteLock() {
                    @Override
                    public void lockForAuthentication() {
                        realLock.lockForAuthentication();
                    }

                    @Override
                    public void lockForUpdate() {
                        writerAttemptingPolicyLock.countDown();
                        realLock.lockForUpdate();
                    }
                };
        var guard = mock(AdministrationPort.class);
        when(guard.require(anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            lockSessionShared();
                            return Set.of("platform:settings:write");
                        });
        doAnswer(
                        invocation -> {
                            lockSessionShared();
                            return null;
                        })
                .when(guard)
                .requireGlobal(anyString(), anyString());

        var methods = mock(AuthenticationMethodAvailabilityPort.class);
        when(methods.availableMethods()).thenReturn(Set.of(PrimaryMethod.PASSWORD));
        var reachability = mock(AuthenticationReachabilityPort.class);
        when(reachability.allActiveAccountsReachable(Set.of(PrimaryMethod.PASSWORD)))
                .thenReturn(true);
        var service =
                new GlobalAuthenticationPolicyService(
                        client,
                        guard,
                        mock(AuditPort.class),
                        notifyingLock,
                        mock(PluginExtensionsPort.class),
                        reachability,
                        methods);

        var transactionManager = new DataSourceTransactionManager(jdbc.getDataSource());
        var previewTransaction = new TransactionTemplate(transactionManager);
        var writerTransaction = new TransactionTemplate(transactionManager);
        var previewHasPolicyShare = new CountDownLatch(1);
        var allowPreviewSessionLock = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var preview =
                    pool.submit(
                            () ->
                                    previewTransaction.execute(
                                            status -> {
                                                realLock.lockForAuthentication();
                                                previewHasPolicyShare.countDown();
                                                await(allowPreviewSessionLock);
                                                client.sql(
                                                                "SELECT state FROM platform_role_preview_context WHERE session_primary_id=:session AND actor_id=:actor FOR UPDATE")
                                                        .param("session", sessionPrimaryId)
                                                        .param("actor", actorId)
                                                        .query(String.class)
                                                        .single();
                                                return true;
                                            }));
            assertTrue(previewHasPolicyShare.await(3, TimeUnit.SECONDS));

            var writer =
                    pool.submit(
                            () ->
                                    writerTransaction.execute(
                                            status ->
                                                    service.save(
                                                            new GlobalAuthenticationPolicyService
                                                                    .Input(
                                                                    1,
                                                                    List.of(PrimaryMethod.PASSWORD),
                                                                    false,
                                                                    "Exercise policy lock order"),
                                                            "admin")));
            assertTrue(writerAttemptingPolicyLock.await(3, TimeUnit.SECONDS));
            allowPreviewSessionLock.countDown();

            assertTrue(preview.get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS));
            var result = writer.get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(2, result.version());
        } finally {
            allowPreviewSessionLock.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void contextLookupCanUseSpringSessionAndContextIndexes() {
        jdbc.execute(
                """
                CREATE TABLE spring_session(
                    primary_id character(36) PRIMARY KEY,
                    session_id character(36) NOT NULL UNIQUE,
                    principal_name character varying(100) NOT NULL,
                    expiry_time bigint NOT NULL)
                """);
        String sessionId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO spring_session VALUES (?,?,?,?)",
                sessionPrimaryId,
                sessionId,
                "admin",
                System.currentTimeMillis() + 60_000);
        for (int index = 0; index < 40; index++) {
            jdbc.update(
                    "INSERT INTO spring_session VALUES (?,?,?,?)",
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    "other",
                    System.currentTimeMillis() + 60_000);
        }
        jdbc.execute(
                "CREATE INDEX platform_role_preview_context_actor_idx ON platform_role_preview_context(actor_id)");
        String plan =
                jdbc.execute(
                        (org.springframework.jdbc.core.ConnectionCallback<String>)
                                connection -> {
                                    try (var statement = connection.createStatement()) {
                                        statement.execute("SET enable_seqscan=off");
                                        try (var explain =
                                                connection.prepareStatement(
                                                        """
                        EXPLAIN (COSTS OFF)
                        SELECT c.session_primary_id
                          FROM spring_session s
                          JOIN platform_role_preview_context c ON c.session_primary_id=s.primary_id
                         WHERE s.session_id=CAST(? AS character(36)) AND s.principal_name=?
                           AND s.expiry_time > ? AND c.actor_id=?
                         FOR UPDATE OF c
                        """)) {
                                            explain.setString(1, sessionId);
                                            explain.setString(2, "admin");
                                            explain.setLong(3, System.currentTimeMillis());
                                            explain.setObject(4, actorId);
                                            try (var result = explain.executeQuery()) {
                                                var lines = new StringBuilder();
                                                while (result.next())
                                                    lines.append(result.getString(1)).append('\n');
                                                return lines.toString();
                                            }
                                        } finally {
                                            statement.execute("RESET enable_seqscan");
                                        }
                                    }
                                });
        assertTrue(
                plan.contains("spring_session_session_id_key")
                        || plan.contains("spring_session_session_id_idx")
                        || plan.contains("spring_session_pkey")
                        || plan.contains("spring_session_session_id_key"),
                plan);
        assertTrue(plan.contains("platform_role_preview_context_pkey"), plan);
    }

    private void lockSessionShared() {
        client.sql(
                        "SELECT state FROM platform_role_preview_context WHERE session_primary_id=:session AND actor_id=:actor FOR SHARE")
                .param("session", sessionPrimaryId)
                .param("actor", actorId)
                .query(String.class)
                .single();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS))
                throw new AssertionError("Timed out waiting for lock-order test synchronization");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted during lock-order test", error);
        }
    }
}
