package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class AdministrationGuardRecoveryConcurrencyDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private AdministrationGuard guard;
    private String schema;
    private UUID userAdminId;
    private UUID roleAdminId;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_admin_recovery_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        guard = new AdministrationGuard(JdbcClient.create(dataSource));
        jdbc.execute(
                "CREATE TABLE platform_user(id uuid PRIMARY KEY,username text,enabled boolean)");
        jdbc.execute(
                "CREATE TABLE platform_effective_authority(user_id uuid,authority text,PRIMARY KEY(user_id,authority))");
        userAdminId = UUID.randomUUID();
        roleAdminId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_user(id,username,enabled) VALUES(?,'user-admin',true),(?,'role-admin',true)",
                userAdminId,
                roleAdminId);
        jdbc.update(
                "INSERT INTO platform_effective_authority(user_id,authority) VALUES(?, 'platform:users:update'), (?, 'platform:roles:update')",
                userAdminId,
                roleAdminId);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void userAndRoleRecoveryWritesRereadGrantsAfterTheSharedLock() throws Exception {
        var firstHasLock = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () ->
                                    tx.executeWithoutResult(
                                            status -> {
                                                guard.require(
                                                        "user-admin", "platform:users:update");
                                                firstHasLock.countDown();
                                                await(releaseFirst);
                                                jdbc.update(
                                                        "DELETE FROM platform_effective_authority WHERE user_id=? AND authority='platform:roles:update'",
                                                        roleAdminId);
                                            }));
            assertTrue(firstHasLock.await(5, TimeUnit.SECONDS));

            var second =
                    pool.submit(
                            () -> {
                                secondStarted.countDown();
                                try {
                                    tx.executeWithoutResult(
                                            status ->
                                                    guard.require(
                                                            "role-admin", "platform:roles:update"));
                                    return true;
                                } catch (ResponseStatusException denied) {
                                    return false;
                                }
                            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertFalse(second.get(5, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Timed out waiting for concurrent transaction");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
