package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestMigration;
import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.common.SecretStorePort;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Opt-in real PostgreSQL suite; each test owns a separate temporary schema. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class SecretStoreIntegrationTest {
    private JdbcTemplate admin, jdbc;
    private SecretStore store;
    private TransactionTemplate transaction;
    private String schema;
    private static final String CONTEXT = "settings:fixture:password";
    private static final byte[] VALUE = {10, 20, 30};

    private static SecretKeys keys() {
        return new SecretKeys(
                "primary=" + Base64.getEncoder().encodeToString(new byte[32]), "primary");
    }

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_secret_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(TestMigration.read("V23__encrypted_secrets.sql"));
        store = new SecretStore(JdbcClient.create(dataSource), keys());
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void storesOnlyCiphertextAndPreservesVersionsAfterClear() {
        assertEquals(new SecretStorePort.State(0, false), store.state(CONTEXT));
        assertTrue(store.reveal(CONTEXT).isEmpty());
        assertEquals(
                new SecretStorePort.State(1, true),
                transaction.execute(s -> store.save(CONTEXT, 0, VALUE)));
        String cipher = jdbc.queryForObject("SELECT ciphertext FROM platform_secret", String.class);
        assertTrue(cipher.startsWith("v1:primary:"));
        assertArrayEquals(VALUE, store.reveal(CONTEXT).orElseThrow());
        assertEquals(
                new SecretStorePort.State(2, false),
                transaction.execute(s -> store.save(CONTEXT, 1, null)));
        assertTrue(store.reveal(CONTEXT).isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM platform_secret", Integer.class));
        assertEquals(
                409,
                assertThrows(
                                ResponseStatusException.class,
                                () -> transaction.execute(s -> store.save(CONTEXT, 0, VALUE)))
                        .getStatusCode()
                        .value());
        assertEquals(
                new SecretStorePort.State(3, true),
                transaction.execute(s -> store.save(CONTEXT, 2, VALUE)));
        jdbc.update(
                "INSERT INTO platform_secret(secret_context,ciphertext,version) VALUES(?,?,1)",
                "settings:another:password",
                cipher);
        assertThrows(IllegalStateException.class, () -> store.reveal("settings:another:password"));
    }

    @Test
    void exactlyOneConcurrentInitialWriterWins() throws Exception {
        var barrier = new CyclicBarrier(2);
        Callable<Integer> write =
                () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        transaction.execute(s -> store.save(CONTEXT, 0, VALUE));
                        return 200;
                    } catch (ResponseStatusException e) {
                        return e.getStatusCode().value();
                    }
                };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(write);
            var second = pool.submit(write);
            var statuses =
                    java.util.stream.Stream.of(
                                    first.get(15, TimeUnit.SECONDS),
                                    second.get(15, TimeUnit.SECONDS))
                            .sorted()
                            .toList();
            assertEquals(java.util.List.of(200, 409), statuses);
            assertEquals(new SecretStorePort.State(1, true), store.state(CONTEXT));
        }
    }

    @Test
    void enclosingFailureRollsBackCredentialAndVersion() {
        transaction.execute(s -> store.save(CONTEXT, 0, VALUE));
        assertThrows(
                IllegalStateException.class,
                () ->
                        transaction.execute(
                                s -> {
                                    store.save(CONTEXT, 1, new byte[] {99});
                                    throw new IllegalStateException("fixture audit failure");
                                }));
        assertEquals(new SecretStorePort.State(1, true), store.state(CONTEXT));
        assertArrayEquals(VALUE, store.reveal(CONTEXT).orElseThrow());
    }

    @Test
    void missingKeysPermitStateInspectionButNeverPlaintextFallback() {
        transaction.execute(s -> store.save(CONTEXT, 0, VALUE));
        var unavailable =
                new SecretStore(
                        JdbcClient.create(jdbc.getDataSource()), new SecretKeys("", "primary"));
        assertTrue(unavailable.state(CONTEXT).configured());
        assertEquals(
                503,
                assertThrows(ResponseStatusException.class, () -> unavailable.reveal(CONTEXT))
                        .getStatusCode()
                        .value());
        assertEquals(
                503,
                assertThrows(
                                ResponseStatusException.class,
                                () -> transaction.execute(s -> unavailable.save(CONTEXT, 1, VALUE)))
                        .getStatusCode()
                        .value());
        transaction.execute(s -> unavailable.save(CONTEXT, 1, null));
        assertFalse(store.state(CONTEXT).configured());
    }
}
