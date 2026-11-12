package com.lumira.plugins.fileconsole;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.*;
import com.lumira.platform.identity.AdministrationGuard;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Actual PostgreSQL guards prove independent file writes overlap while exclusive administration
 * waits.
 */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class ManagedFileConcurrencyDatabaseTest extends com.lumira.testfixtures.FileQueryFixture {
    private ManagedFileRepository files;
    private DataSourceTransactionManager manager;
    private AdministrationGuard guard;

    @BeforeEach
    void prepareAuthorization() {
        for (String table :
                List.of(
                        "platform_authority",
                        "platform_role",
                        "platform_user_role",
                        "platform_role_authority",
                        "platform_permission_retired_authority")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
        for (String view : List.of("platform_effective_authority", "platform_global_authority"))
            jdbc.execute(TestSchema.view(view));
        for (UUID actor : List.of(alice, bob))
            for (String permission : FileConsoleAuthorityCodes.values())
                jdbc.update(
                        "INSERT INTO platform_authority(user_id,authority) VALUES(?,?)",
                        actor,
                        permission);
        files = new ManagedFileRepository(client);
        manager = new DataSourceTransactionManager(jdbc.getDataSource());
        guard = new AdministrationGuard(client);
    }

    @Test
    void distinctFileWritesOverlapAndKeepTheExclusiveRecoveryBarrierUntilCommit() throws Exception {
        var firstId = add(alice, "first.txt", "READY", "2026-02-01");
        var secondId = add(bob, "second.txt", "READY", "2026-02-02");
        var audit = mock(AuditPort.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writerStarted = new CountDownLatch(1);
        var writerPid = new AtomicInteger();
        doAnswer(
                        call -> {
                            entered.countDown();
                            if (!release.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("Release timed out");
                            return null;
                        })
                .when(audit)
                .append("alice", "file.trash", firstId);
        var service =
                new ManagedFileService(
                        files,
                        mock(FileManagementStoragePort.class),
                        manager,
                        audit,
                        guard,
                        () -> {});
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = null, exclusive = null;
            try {
                first = executor.submit(() -> service.deleteManaged("alice", firstId, 0));
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                executor.submit(() -> service.deleteManaged("bob", secondId, 0))
                        .get(5, TimeUnit.SECONDS);
                exclusive =
                        executor.submit(
                                () ->
                                        new TransactionTemplate(manager)
                                                .executeWithoutResult(
                                                        status -> {
                                                            writerPid.set(
                                                                    jdbc.queryForObject(
                                                                            "SELECT pg_backend_pid()",
                                                                            Integer.class));
                                                            writerStarted.countDown();
                                                            guard.requireOperationGlobal(
                                                                    "alice",
                                                                    PlatformOperation.FILE_DELETE);
                                                        }));
                assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (System.nanoTime() < deadline && !exclusive.isDone()) {
                    waiting =
                            Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND locktype='advisory' AND NOT granted)",
                                            Boolean.class,
                                            writerPid.get()));
                    if (waiting) break;
                    Thread.sleep(20);
                }
                assertTrue(
                        waiting,
                        "Exclusive administration must wait while the first file transaction is still open");
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            exclusive.get(5, TimeUnit.SECONDS);
        }
        assertEquals("TRASHED", files.findManaged(firstId).orElseThrow().state());
        assertEquals("TRASHED", files.findManaged(secondId).orElseThrow().state());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(
            value = PlatformOperation.class,
            names = {"FILE_DELETE", "FILE_RESTORE", "FILE_PURGE"})
    void scopedOnlyOrRevokedOperationsNeverMutateFiles(PlatformOperation operation) {
        UUID role = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_role(id,code,name,data_scope) VALUES(?,'scoped','Scoped','SELF')",
                role);
        jdbc.update("INSERT INTO platform_user_role(user_id,role_id) VALUES(?,?)", alice, role);
        jdbc.update(
                "DELETE FROM platform_authority WHERE user_id=? AND authority=?",
                alice,
                operation.permission());
        jdbc.update(
                "INSERT INTO platform_role_authority(role_id,authority) VALUES(?,?)",
                role,
                operation.permission());
        UUID id =
                add(
                        alice,
                        "denied.txt",
                        operation == PlatformOperation.FILE_DELETE ? "READY" : "TRASHED",
                        "2026-03-01");
        var storage = mock(FileManagementStoragePort.class);
        var service =
                new ManagedFileService(
                        files, storage, manager, mock(AuditPort.class), guard, () -> {});
        var before = files.findManaged(id).orElseThrow();
        Runnable action =
                switch (operation) {
                    case FILE_DELETE -> () -> service.deleteManaged("alice", id, before.version());
                    case FILE_RESTORE ->
                            () -> service.restoreManaged("alice", id, before.version());
                    case FILE_PURGE -> () -> service.purgeManaged("alice", id, before.version());
                    default -> throw new AssertionError();
                };
        assertEquals(
                403,
                assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
        jdbc.update("DELETE FROM platform_role_authority WHERE role_id=?", role);
        assertEquals(
                403,
                assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
        assertEquals(before, files.findManaged(id).orElseThrow());
        verifyNoInteractions(storage);
    }
}
