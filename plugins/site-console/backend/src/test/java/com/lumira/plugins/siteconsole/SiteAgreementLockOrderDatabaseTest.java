package com.lumira.plugins.siteconsole;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.*;
import com.lumira.platform.identity.IdentityAuthenticationPolicyWriteLock;
import com.lumira.platform.settings.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Three real transactions cover registration, publication and a queued policy mutation. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class SiteAgreementLockOrderDatabaseTest {
    private JdbcTemplate observer;
    private JdbcTemplate jdbc;
    private String schema;
    private TransactionTemplate tx;

    @BeforeEach
    void prepare() {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        observer = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_site_locks_" + UUID.randomUUID().toString().replace("-", "");
        observer.execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(source);
        jdbc.execute(TestSchema.table("platform_authentication_policy_lock"));
        TestSchema.indexes("platform_authentication_policy_lock").forEach(jdbc::execute);
        jdbc.update("INSERT INTO platform_authentication_policy_lock(id) VALUES(1)");
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterEach
    void cleanup() {
        if (observer != null && schema != null)
            observer.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private void awaitLockWait(AtomicInteger pid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (pid.get() != 0
                    && Boolean.TRUE.equals(
                            observer.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",
                                    Boolean.class,
                                    pid.get()))) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
        }
        fail("Expected transaction to wait on a real PostgreSQL lock");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(20)
    void publicationCompletesAlongsideRegistrationAndAQueuedPolicyMutation(boolean rollback)
            throws Exception {
        var policy =
                new IdentityAuthenticationPolicyWriteLock(JdbcClient.create(jdbc.getDataSource()));
        var agreements =
                new AgreementSnapshotService(
                        JdbcClient.create(jdbc.getDataSource()), mock(SettingDocuments.class));
        var guard = mock(AdministrationPort.class);
        // The production administration boundary takes this same authentication-policy share lock.
        doAnswer(
                        call -> {
                            policy.lockForAuthentication();
                            return Set.of();
                        })
                .when(guard)
                .requireOperationGlobal("admin", PlatformOperation.AGREEMENTS_UPDATE);
        var mapper = new JsonMapper();
        var data = new Agreements("Retained terms", "Retained privacy");
        var payload = mapper.writeValueAsString(data);
        var store = mock(SettingStore.class);
        when(store.revision("agreements", 1))
                .thenReturn(
                        Optional.of(
                                new SettingStore.Revision(
                                        1,
                                        payload,
                                        "admin",
                                        "Publication",
                                        OffsetDateTime.now(),
                                        null)));
        when(store.save("agreements", payload, "admin", 2, "Restore", 1L))
                .thenReturn(new SettingStore.Snapshot(UUID.randomUUID(), 3, payload));
        var documents = mock(SettingDocuments.class);
        when(documents.save(
                        eq("agreements"),
                        eq(2L),
                        eq(data),
                        eq("Publish"),
                        eq("admin"),
                        eq("invalidAgreements"),
                        any()))
                .thenAnswer(
                        call -> {
                            guard.requireOperationGlobal(
                                    "admin", PlatformOperation.AGREEMENTS_UPDATE);
                            return new SettingDocuments.Snapshot<>(3, data);
                        });
        var actor = mock(Authentication.class);
        when(actor.getName()).thenReturn("admin");
        Runnable publish =
                rollback
                        ? () ->
                                new SiteSettingRollbackService(
                                                store,
                                                mapper,
                                                guard,
                                                mock(MaintenanceService.class),
                                                mock(BrandingAssetReferences.class),
                                                agreements)
                                        .rollback(
                                                "agreements",
                                                new SiteSettingRollbackService.Input(
                                                        2, 1, "Restore"),
                                                "admin")
                        : () ->
                                new AgreementController(documents, agreements, guard)
                                        .write(
                                                new AgreementController.Input(2, data, "Publish"),
                                                actor);
        var policyPid = new AtomicInteger();
        var tasks = new ArrayList<Future<?>>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                tx.executeWithoutResult(
                        status -> {
                            jdbc.execute("SET LOCAL statement_timeout='5s'");
                            policy.lockForAuthentication(); // Registration's first lock.
                            tasks.add(
                                    pool.submit(
                                            (Runnable)
                                                    () ->
                                                            tx.executeWithoutResult(
                                                                    other -> {
                                                                        jdbc.execute(
                                                                                "SET LOCAL statement_timeout='5s'");
                                                                        policyPid.set(
                                                                                jdbc.queryForObject(
                                                                                        "SELECT pg_backend_pid()",
                                                                                        Integer
                                                                                                .class));
                                                                        policy
                                                                                .lockForUpdate(); // A queued role/authentication mutation.
                                                                    })));
                            awaitLockWait(policyPid);
                            tasks.add(
                                    pool.submit(
                                            (Runnable)
                                                    () ->
                                                            tx.executeWithoutResult(
                                                                    other -> {
                                                                        jdbc.execute(
                                                                                "SET LOCAL statement_timeout='5s'");
                                                                        publish.run();
                                                                    })));
                            try {
                                tasks.get(1).get(3, TimeUnit.SECONDS);
                            } catch (Exception failure) {
                                throw new AssertionError(
                                        "Compatible publication must finish while registration holds its share lock",
                                        failure);
                            }
                            assertFalse(
                                    tasks.get(0).isDone(),
                                    "The exclusive policy mutation must still wait for registration");
                            assertEquals(
                                    Boolean.TRUE,
                                    jdbc.queryForObject(
                                            "SELECT pg_try_advisory_xact_lock_shared(481029)",
                                            Boolean.class),
                                    "Registration can capture its agreement after publication and finish");
                        });
            } finally {
                for (var task : tasks) task.get(8, TimeUnit.SECONDS);
            }
        }
    }
}
