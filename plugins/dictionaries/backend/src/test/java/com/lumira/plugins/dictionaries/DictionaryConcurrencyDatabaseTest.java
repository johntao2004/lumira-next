package com.lumira.plugins.dictionaries;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.identity.AdministrationGuard;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class DictionaryConcurrencyDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private TransactionTemplate transactions;
    private AdministrationGuard guard;
    private DictionaryRepository repository;
    private DictionaryService service;
    private ExecutorService tasks;
    private final AtomicReference<Consumer<String>> audit = new AtomicReference<>(action -> {});

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_dictionary_concurrent_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(source);
        for (String table :
                List.of(
                        "platform_user",
                        "platform_authority",
                        "platform_role",
                        "platform_user_role",
                        "platform_role_authority",
                        "platform_permission_retired_authority",
                        "platform_dictionary",
                        "platform_dictionary_item",
                        "platform_setting")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
        TestSchema.foreignKeys("platform_dictionary_item").forEach(jdbc::execute);
        for (String view : List.of("platform_effective_authority", "platform_global_authority"))
            jdbc.execute(TestSchema.view(view));
        jdbc.execute(
                "INSERT INTO platform_user(id,username,display_name,password_hash) VALUES (gen_random_uuid(),'writer','Writer','fixture')");
        jdbc.execute(
                "INSERT INTO platform_authority(user_id,authority) SELECT id,'platform:dictionaries:'||action FROM platform_user CROSS JOIN unnest(ARRAY['access','create','update','delete','import']) action");
        jdbc.execute(
                "INSERT INTO platform_authority(user_id,authority) SELECT id,'platform:profile-fields:'||action FROM platform_user CROSS JOIN unnest(ARRAY['access','update']) action");
        var client = JdbcClient.create(source);
        guard = new AdministrationGuard(client);
        repository = new DictionaryRepository(client);
        service =
                new DictionaryService(
                        repository, guard, (actor, action, target) -> audit.get().accept(action));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        transactions.setTimeout(10);
        tasks = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (tasks != null) {
            tasks.shutdownNow();
            assertTrue(
                    tasks.awaitTermination(12, TimeUnit.SECONDS), "Fixture writers did not stop");
        }
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void independentCreatesReachTheCommitBoundaryTogether() throws Exception {
        var together = new CyclicBarrier(2);
        audit.set(action -> meet(together));
        var first = submit(() -> service.create(input("first", "First", null), "writer"));
        var second = submit(() -> service.create(input("second", "Second", null), "writer"));
        assertNotEquals(
                first.get(10, TimeUnit.SECONDS).id(), second.get(10, TimeUnit.SECONDS).id());
        assertEquals(
                2, jdbc.queryForObject("SELECT count(*) FROM platform_dictionary", Integer.class));
    }

    @Test
    void independentUpdatesDoNotShareASectionMutex() throws Exception {
        var first = seed("first");
        var second = seed("second");
        var together = new CyclicBarrier(2);
        audit.set(action -> meet(together));
        var one =
                submit(
                        () ->
                                service.update(
                                        first.id(), input(null, "One", first.version()), "writer"));
        var two =
                submit(
                        () ->
                                service.update(
                                        second.id(),
                                        input(null, "Two", second.version()),
                                        "writer"));
        assertEquals(first.version() + 1, one.get(10, TimeUnit.SECONDS).version());
        assertEquals(second.version() + 1, two.get(10, TimeUnit.SECONDS).version());
    }

    @Test
    void independentDeletesReachTheCommitBoundaryTogether() throws Exception {
        var first = seed("first");
        var second = seed("second");
        var together = new CyclicBarrier(2);
        audit.set(action -> meet(together));
        var one =
                submit(
                        () -> {
                            service.delete(first.id(), first.version(), "writer");
                            return true;
                        });
        var two =
                submit(
                        () -> {
                            service.delete(second.id(), second.version(), "writer");
                            return true;
                        });
        assertTrue(one.get(10, TimeUnit.SECONDS));
        assertTrue(two.get(10, TimeUnit.SECONDS));
        assertEquals(
                0, jdbc.queryForObject("SELECT count(*) FROM platform_dictionary", Integer.class));
    }

    @Test
    void concurrentDuplicateCreatesStillHaveOneDatabaseEffect() throws Exception {
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        audit.set(
                action -> {
                    inserted.countDown();
                    await(release);
                });
        var first = submit(() -> service.create(input("duplicate", "First", null), "writer"));
        try {
            await(inserted);
            var second = submit(() -> service.create(input("duplicate", "Second", null), "writer"));
            assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            var error =
                    assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
            assertInstanceOf(DataIntegrityViolationException.class, error.getCause());
            assertEquals(
                    1,
                    jdbc.queryForObject("SELECT count(*) FROM platform_dictionary", Integer.class));
        } finally {
            release.countDown();
        }
    }

    @Test
    void sameRecordUpdateAndDeleteCannotBothConsumeOneVersion() throws Exception {
        var old = seed("same_record");
        var updated = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        audit.set(
                action -> {
                    updated.countDown();
                    await(release);
                });
        var first =
                submit(
                        () ->
                                service.update(
                                        old.id(), input(null, "Updated", old.version()), "writer"));
        try {
            await(updated);
            var second =
                    submit(
                            () -> {
                                service.delete(old.id(), old.version(), "writer");
                                return true;
                            });
            assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            var committed = first.get(10, TimeUnit.SECONDS);
            var error =
                    assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
            var conflict = assertInstanceOf(ResponseStatusException.class, error.getCause());
            assertEquals(409, conflict.getStatusCode().value());
            assertEquals("staleRecord", conflict.getReason());
            assertEquals(committed, repository.find(old.id()));
        } finally {
            release.countDown();
        }
    }

    @Test
    void legacyProfileWriterStillProtectsReferencesAgainstConcurrentDeletion() throws Exception {
        var dictionary = seed("referenced");
        var changed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first =
                submit(
                        () -> {
                            guard.requireOperationGlobal(
                                    "writer", PlatformOperation.PROFILE_FIELDS_UPDATE);
                            jdbc.update(
                                    "INSERT INTO platform_setting(id,section,version,payload,updated_by) VALUES (gen_random_uuid(),'profile-fields',1,jsonb_build_object('fields',jsonb_build_array(jsonb_build_object('dictionaryCode',?::text))),'writer')",
                                    dictionary.code());
                            changed.countDown();
                            await(release);
                            return true;
                        });
        try {
            await(changed);
            var second =
                    submit(
                            () -> {
                                service.delete(dictionary.id(), dictionary.version(), "writer");
                                return true;
                            });
            assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            var error =
                    assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
            var conflict = assertInstanceOf(ResponseStatusException.class, error.getCause());
            assertEquals("dictionaryReferenced", conflict.getReason());
            assertEquals(dictionary, repository.find(dictionary.id()));
        } finally {
            release.countDown();
        }
    }

    @Test
    void concurrentModeStillRequiresBothGrantsAndAWritableTransaction() throws Exception {
        assertThrows(
                IllegalStateException.class,
                () -> service.create(input("outside_tx", "Outside", null), "writer"));
        for (String permission :
                List.of("platform:dictionaries:access", "platform:dictionaries:create")) {
            jdbc.update("DELETE FROM platform_authority WHERE authority=?", permission);
            var result =
                    submit(() -> service.create(input("forbidden", "Forbidden", null), "writer"));
            var error =
                    assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
            assertEquals(
                    403,
                    assertInstanceOf(ResponseStatusException.class, error.getCause())
                            .getStatusCode()
                            .value());
            jdbc.update(
                    "INSERT INTO platform_authority(user_id,authority) SELECT id,? FROM platform_user",
                    permission);
        }
        assertEquals(
                0, jdbc.queryForObject("SELECT count(*) FROM platform_dictionary", Integer.class));
    }

    @Test
    void concurrentModeDoesNotPromoteScopedGrantsToGlobalGrants() throws Exception {
        jdbc.execute(
                "INSERT INTO platform_role(id,code,name,data_scope) VALUES (gen_random_uuid(),'scoped','Scoped','SELF')");
        jdbc.execute(
                "INSERT INTO platform_user_role(user_id,role_id) SELECT u.id,r.id FROM platform_user u CROSS JOIN platform_role r");
        for (String permission :
                List.of("platform:dictionaries:access", "platform:dictionaries:create")) {
            jdbc.update("DELETE FROM platform_authority WHERE authority=?", permission);
            jdbc.execute("DELETE FROM platform_role_authority");
            jdbc.update(
                    "INSERT INTO platform_role_authority(role_id,authority) SELECT id,? FROM platform_role",
                    permission);
            var result = submit(() -> service.create(input("scoped", "Scoped", null), "writer"));
            var error =
                    assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
            assertEquals(
                    403,
                    assertInstanceOf(ResponseStatusException.class, error.getCause())
                            .getStatusCode()
                            .value());
            jdbc.update(
                    "INSERT INTO platform_authority(user_id,authority) SELECT id,? FROM platform_user",
                    permission);
        }
        assertEquals(
                0, jdbc.queryForObject("SELECT count(*) FROM platform_dictionary", Integer.class));
    }

    @Test
    void concurrentGuardDoesNotRetainGrantsAfterRevocation() throws Exception {
        submit(() -> service.create(input("before_revoke", "Before", null), "writer"))
                .get(10, TimeUnit.SECONDS);
        jdbc.execute(
                "DELETE FROM platform_authority WHERE authority='platform:dictionaries:create'");
        var denied = submit(() -> service.create(input("after_revoke", "After", null), "writer"));
        var error = assertThrows(ExecutionException.class, () -> denied.get(10, TimeUnit.SECONDS));
        assertEquals(
                403,
                assertInstanceOf(ResponseStatusException.class, error.getCause())
                        .getStatusCode()
                        .value());
        assertEquals(
                1, jdbc.queryForObject("SELECT count(*) FROM platform_dictionary", Integer.class));
    }

    private DictionaryRepository.Dictionary seed(String code) {
        UUID id = UUID.randomUUID();
        repository.create(id, code, code, true, "FLAT", "");
        return repository.find(id);
    }

    private <T> Future<T> submit(Supplier<T> work) {
        return tasks.submit(
                () ->
                        transactions.execute(
                                status -> {
                                    jdbc.execute("SET LOCAL lock_timeout='5s'");
                                    return work.get();
                                }));
    }

    private static DictionaryService.Input input(String code, String name, Long version) {
        return new DictionaryService.Input(code, name, true, "FLAT", "", version);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(8, TimeUnit.SECONDS), "Fixture boundary timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static void meet(CyclicBarrier barrier) {
        try {
            barrier.await(8, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new AssertionError("Independent writers did not overlap", error);
        }
    }
}
