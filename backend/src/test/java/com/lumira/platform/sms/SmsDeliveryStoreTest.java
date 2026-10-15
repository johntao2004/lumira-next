package com.lumira.platform.sms;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.SecretKeys;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class SmsDeliveryStoreTest {
    JdbcTemplate admin, jdbc;
    TransactionTemplate tx;
    SmsDeliveryStore store;
    String schema;
    private final OutgoingSms sms = new OutgoingSms("+8613800138000", "Code 123");

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_sms_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(TestSchema.table("platform_sms_delivery"));
        TestSchema.indexes("platform_sms_delivery").forEach(jdbc::execute);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        store =
                new SmsDeliveryStore(
                        JdbcClient.create(ds),
                        new SecretKeys(
                                "primary=" + Base64.getEncoder().encodeToString(new byte[32]),
                                "primary"),
                        new tools.jackson.databind.ObjectMapper());
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    UUID enqueue() {
        UUID id = UUID.randomUUID();
        assertTrue(tx.<Boolean>execute(s -> store.enqueue(id, sms, 3600)));
        return id;
    }

    SmsDeliveryStore.Claim claim() {
        return tx.execute(s -> store.claim().orElseThrow());
    }

    @Test
    void payloadIsEncryptedAndClaimTokenFencesCompletion() {
        UUID id = enqueue();
        String cipher =
                jdbc.queryForObject("SELECT payload FROM platform_sms_delivery", String.class);
        assertTrue(cipher.startsWith("v1:primary:"));
        assertFalse(cipher.contains("Code 123"));
        var claim = claim();
        assertEquals(sms, store.message(claim).orElseThrow());
        assertTrue(tx.<Boolean>execute(s -> store.beginSending(claim)));
        assertTrue(tx.<Boolean>execute(s -> store.complete(claim, SmsGateway.Result.ACCEPTED)));
        assertEquals("ACCEPTED", store.status(id).orElseThrow().state());
        assertFalse(tx.<Boolean>execute(s -> store.complete(claim, SmsGateway.Result.ACCEPTED)));
    }

    @Test
    void uncertainDeliveryIsTerminalAndConnectionFailuresBackOff() {
        UUID id = enqueue();
        var claim = claim();
        tx.execute(s -> store.beginSending(claim));
        assertTrue(
                tx.<Boolean>execute(
                        s -> store.complete(claim, SmsGateway.Result.DELIVERY_UNCERTAIN)));
        assertEquals("UNCERTAIN", store.status(id).orElseThrow().state());
        assertTrue(store.claim().isEmpty());
        UUID retry = enqueue();
        for (int attempt = 1; attempt <= 5; attempt++) {
            var c = claim();
            tx.execute(s -> store.beginSending(c));
            tx.execute(s -> store.complete(c, SmsGateway.Result.CONNECTION_FAILED));
            if (attempt < 5)
                jdbc.update(
                        "UPDATE platform_sms_delivery SET available_at=now()-interval '1 second' WHERE id=?",
                        retry);
        }
        assertEquals("FAILED", store.status(retry).orElseThrow().state());
    }

    @Test
    void abandonedSendingBecomesUncertain() {
        UUID id = enqueue();
        var claim = claim();
        tx.execute(s -> store.beginSending(claim));
        jdbc.update(
                "UPDATE platform_sms_delivery SET lease_until=now()-interval '1 second' WHERE id=?",
                id);
        assertEquals(
                List.of(new SmsDeliveryStore.Recovery(id, "UNCERTAIN")),
                tx.execute(s -> store.recoverExpired()));
        assertTrue(store.claim().isEmpty());
    }

    @Test
    void liveSendingLeaseCanBeRenewedButExpiredLeaseCannot() {
        UUID id = enqueue();
        var claim = claim();
        assertTrue(tx.<Boolean>execute(s -> store.beginSending(claim)));
        assertTrue(tx.<Boolean>execute(s -> store.renewLease(claim)));
        jdbc.update(
                "UPDATE platform_sms_delivery SET lease_until=now()-interval '1 second' WHERE id=?",
                id);
        assertFalse(tx.<Boolean>execute(s -> store.renewLease(claim)));
    }

    @Test
    void retentionDeletesOnlyOldTerminalRows() {
        UUID terminal = enqueue();
        var claim = claim();
        assertTrue(tx.<Boolean>execute(s -> store.beginSending(claim)));
        assertTrue(tx.<Boolean>execute(s -> store.complete(claim, SmsGateway.Result.ACCEPTED)));
        jdbc.update(
                "UPDATE platform_sms_delivery SET updated_at=now()-interval '31 days' WHERE id=?",
                terminal);
        UUID pending = enqueue();
        assertEquals(1, tx.<Integer>execute(s -> store.purgeTerminal(Duration.ofDays(30), 100)));
        assertTrue(store.status(terminal).isEmpty());
        assertEquals("PENDING", store.status(pending).orElseThrow().state());
    }

    @Test
    void structuredCodeSurvivesEncryptionAndProviderRejectionIsTerminal() {
        UUID id = UUID.randomUUID();
        var message = new OutgoingSms("+14155552671", "Code 002569", "002569", 600, "a".repeat(64));
        assertTrue(tx.<Boolean>execute(s -> store.enqueue(id, message, 600)));
        String encrypted =
                jdbc.queryForObject(
                        "SELECT payload FROM platform_sms_delivery WHERE id=?", String.class, id);
        assertFalse(encrypted.contains("002569"));
        assertFalse(encrypted.contains("14155552671"));
        var claim = claim();
        assertEquals(message, store.message(claim).orElseThrow());
        assertTrue(tx.<Boolean>execute(s -> store.beginSending(claim)));
        assertTrue(
                tx.<Boolean>execute(
                        s -> store.complete(claim, SmsGateway.Result.PROVIDER_REJECTED)));
        assertEquals("FAILED", store.status(id).orElseThrow().state());
        assertTrue(store.claim().isEmpty());
    }
}
