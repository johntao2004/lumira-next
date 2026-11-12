package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.TestSchema;
import com.lumira.platform.identity.api.AccountProvisioningPort;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class AccountProvisioningDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private JdbcClient client;
    private String schema;
    private TransactionTemplate transactions;
    private AccountProvisioningAdapter accounts;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_account_provision_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(source);
        client = JdbcClient.create(source);
        jdbc.execute(TestSchema.table("platform_user"));
        TestSchema.indexes("platform_user").forEach(jdbc::execute);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var encoder = mock(PasswordEncoder.class);
        when(encoder.encode("Valid-password-31")).thenReturn("encoded-fixture");
        accounts =
                new AccountProvisioningAdapter(
                        new AccountRegistrationRepository(client),
                        encoder,
                        SecurityPolicyPort.Policy::defaults);
    }

    @AfterEach
    void cleanFixture() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void registrationCreatesOnlyAnEncodedAccountWithoutDirectoryAdministration() {
        var prepared = accounts.preparePassword("Valid-password-31");
        UUID id = accounts.provision("new-user", "  New user  ", prepared);
        assertEquals(
                "New user|encoded-fixture",
                jdbc.queryForObject(
                        "SELECT display_name || '|' || password_hash FROM platform_user WHERE id=?",
                        String.class,
                        id));
        assertEquals(
                409,
                assertThrows(
                                ResponseStatusException.class,
                                () -> accounts.provision("new-user", "Other", prepared))
                        .getStatusCode()
                        .value());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM platform_user", Integer.class));
    }

    @Test
    void registrationSharesTheCallerTransactionAndRollsBackCompletely() {
        transactions.executeWithoutResult(
                status -> {
                    accounts.provision(
                            "rollback-user",
                            "Rollback",
                            new AccountProvisioningPort.PreparedPassword("encoded"));
                    status.setRollbackOnly();
                });
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM platform_user", Integer.class));
        assertThrows(ResponseStatusException.class, () -> accounts.preparePassword("short"));
        assertThrows(
                ResponseStatusException.class,
                () -> accounts.provision("invalid space", "Name", null));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM platform_user", Integer.class));
    }

    @Test
    void concurrentRegistrationForTheSameNameHasExactlyOneWinner() throws Exception {
        var start = new CountDownLatch(1);
        Callable<Integer> attempt =
                () -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        accounts.provision(
                                "concurrent-user",
                                "Concurrent",
                                new AccountProvisioningPort.PreparedPassword("encoded"));
                        return 201;
                    } catch (ResponseStatusException conflict) {
                        return conflict.getStatusCode().value();
                    }
                };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            assertEquals(
                    java.util.Set.of(201, 409),
                    java.util.Set.of(
                            first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM platform_user", Integer.class));
    }

    @Test
    void accountVersionSnapshotDoesNotWaitForAnUnrelatedWriterAndRejectsDeletedAccounts() {
        UUID id =
                accounts.provision(
                        "version-user",
                        "Version",
                        new AccountProvisioningPort.PreparedPassword("encoded"));
        var versions = new AccountVersionAdapter(client);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            transactions.executeWithoutResult(
                    status -> {
                        assertEquals(0L, versions.version(id));
                        var snapshot = executor.submit(() -> versions.versionSnapshot(id));
                        try {
                            assertEquals(0L, snapshot.get(2, TimeUnit.SECONDS));
                        } catch (Exception failure) {
                            throw new AssertionError(
                                    "Read-only account snapshot waited for the writer lock",
                                    failure);
                        }
                    });
        }
        jdbc.update("UPDATE platform_user SET deleted_at=now() WHERE id=?", id);
        assertEquals(
                404,
                assertThrows(ResponseStatusException.class, () -> versions.version(id))
                        .getStatusCode()
                        .value());
        assertEquals(
                404,
                assertThrows(ResponseStatusException.class, () -> versions.versionSnapshot(id))
                        .getStatusCode()
                        .value());
    }
}
