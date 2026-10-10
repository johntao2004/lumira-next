package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestMigration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class AccountPhoneStoreTest {
    JdbcTemplate admin, jdbc;
    TransactionTemplate tx;
    AccountPhoneStore store;
    String schema;
    UUID owner = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_phone_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE platform_user(id UUID PRIMARY KEY)");
        jdbc.update("INSERT INTO platform_user(id) VALUES(?)", owner);
        String ddl = TestMigration.read("V47__sms_delivery_and_account_phone.sql");
        jdbc.execute(
                ddl.substring(
                        ddl.indexOf("CREATE TABLE platform_account_phone"),
                        ddl.indexOf("-- The settings page")));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        store = new AccountPhoneStore(JdbcClient.create(ds));
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    VerificationToken.Issued request(long version, String phone) {
        var token = VerificationToken.issue();
        tx.execute(s -> store.request(owner, version, phone, token));
        return token;
    }

    boolean confirm(String token) {
        return Boolean.TRUE.equals(tx.execute(s -> store.confirm(owner, token)));
    }

    @Test
    void pendingPhoneIsSingleUseAndVersioned() {
        var token = request(0, "+8613800138000");
        assertNull(store.read(owner).phone());
        assertEquals("+8613800138000", store.read(owner).pendingPhone());
        assertTrue(confirm(token.value()));
        assertEquals("+8613800138000", store.read(owner).phone());
        assertFalse(confirm(token.value()));
        assertEquals(2, store.read(owner).version());
    }

    @Test
    void expiryFailuresCooldownAndDailyLimitAreEnforced() {
        var token = request(0, "+8613800138000");
        for (int i = 0; i < 5; i++) assertFalse(confirm("invalid"));
        assertFalse(confirm(token.value()));
        jdbc.update(
                "UPDATE platform_account_phone SET requested_at=now()-interval '2 minutes',request_count=10");
        assertEquals(
                429,
                assertThrows(ResponseStatusException.class, () -> request(1, "+8613800138001"))
                        .getStatusCode()
                        .value());
        jdbc.update("UPDATE platform_account_phone SET request_day=CURRENT_DATE-1");
        var next = request(1, "+8613800138001");
        jdbc.update("UPDATE platform_account_phone SET expires_at=now()-interval '1 second'");
        assertFalse(confirm(next.value()));
    }

    @Test
    void concurrentConfirmationSucceedsExactlyOnce() throws Exception {
        var token = request(0, "+8613800138000");
        var barrier = new CyclicBarrier(2);
        Callable<Boolean> action =
                () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return confirm(token.value());
                };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(action);
            var b = pool.submit(action);
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void invalidPhoneAndStaleVersionDoNotWrite() {
        assertThrows(ResponseStatusException.class, () -> request(0, "13800138000"));
        request(0, "+8613800138000");
        assertEquals(
                409,
                assertThrows(ResponseStatusException.class, () -> request(0, "+8613800138001"))
                        .getStatusCode()
                        .value());
        assertEquals(
                1,
                jdbc.queryForObject("SELECT count(*) FROM platform_account_phone", Integer.class));
    }

    @Test
    void numericCodesPersistKeyedDigestsAndRemainSingleUse() {
        var codes =
                new PhoneVerificationCode(
                        new com.lumira.platform.common.SecretKeys(
                                "primary=" + Base64.getEncoder().encodeToString(new byte[32]),
                                "primary"));
        store = new AccountPhoneStore(JdbcClient.create(jdbc.getDataSource()), null, codes);
        var token = codes.issue(owner);
        tx.execute(s -> store.request(owner, 0, "+14155552671", token));
        String saved =
                jdbc.queryForObject(
                        "SELECT token_hash FROM platform_account_phone WHERE user_id=?",
                        String.class,
                        owner);
        assertEquals(token.digest(), saved);
        assertNotEquals(VerificationToken.digest(token.value()), saved);
        assertFalse(confirm("wrong"));
        assertTrue(confirm(token.value()));
        assertFalse(confirm(token.value()));
        assertEquals("+14155552671", store.read(owner).phone());
    }
}
