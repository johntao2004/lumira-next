package com.lumira.platform.mail;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.SecretKeys;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class MailDeliveryStoreTest {
    JdbcTemplate admin, jdbc;
    TransactionTemplate tx;
    MailDeliveryStore store;
    String schema;
    private final OutgoingMail mail =
            new OutgoingMail("fixture@example.test", "Private subject", "Private body");

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_mail_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(TestSchema.table("platform_mail_delivery"));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        store =
                new MailDeliveryStore(
                        JdbcClient.create(ds),
                        new SecretKeys(
                                "primary=" + Base64.getEncoder().encodeToString(new byte[32]),
                                "primary"),
                        new ObjectMapper());
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    UUID enqueue() {
        UUID id = UUID.randomUUID();
        assertTrue(tx.<Boolean>execute(s -> store.enqueue(id, mail, 3600)));
        return id;
    }

    MailDeliveryStore.Claim claim() {
        return tx.execute(s -> store.claim().orElseThrow());
    }

    String state(UUID id) {
        return store.status(id).orElseThrow().state();
    }

    @Test
    void encryptedIdempotentMessageParticipatesInCallerTransaction() {
        UUID id = enqueue();
        String cipher =
                jdbc.queryForObject("SELECT payload FROM platform_mail_delivery", String.class);
        assertTrue(cipher.startsWith("v1:primary:"));
        assertFalse(cipher.contains("Private"));
        assertFalse(
                tx.<Boolean>execute(
                        s ->
                                store.enqueue(
                                        id,
                                        new OutgoingMail(
                                                "other@example.test", "Changed", "Changed"),
                                        30)));
        var claim = claim();
        assertEquals(mail, store.message(claim).orElseThrow());
        assertEquals(1, claim.attempts());
        UUID rolledBack = UUID.randomUUID();
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.execute(
                                s -> {
                                    store.enqueue(rolledBack, mail, 3600);
                                    throw new IllegalStateException("Audit failure");
                                }));
        assertTrue(store.status(rolledBack).isEmpty());
        UUID copied = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_mail_delivery(id,payload,expires_at) VALUES(?,?,now()+interval '1 hour')",
                copied,
                cipher);
        assertThrows(IllegalStateException.class, () -> store.message(claim()));
    }

    @Test
    void onlyOneConcurrentWorkerClaimsTheSameMessage() throws Exception {
        UUID id = enqueue();
        var barrier = new CyclicBarrier(2);
        Callable<Optional<MailDeliveryStore.Claim>> action =
                () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return tx.execute(s -> store.claim());
                };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(action);
            var two = pool.submit(action);
            var results = List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(Optional::isPresent).count());
            assertEquals(1, store.status(id).orElseThrow().attempts());
        }
    }

    @Test
    void expiredClaimCanBeRetakenButOldOwnerCannotSend() {
        UUID id = enqueue();
        var old = claim();
        jdbc.update(
                "UPDATE platform_mail_delivery SET lease_until=now()-interval '1 second' WHERE id=?",
                id);
        assertFalse(tx.<Boolean>execute(s -> store.beginSending(old)));
        assertEquals(1, tx.<Integer>execute(s -> store.recoverExpired()));
        var current = claim();
        assertNotEquals(old.token(), current.token());
        assertTrue(store.message(old).isEmpty());
        assertFalse(tx.<Boolean>execute(s -> store.beginSending(old)));
        assertTrue(tx.<Boolean>execute(s -> store.beginSending(current)));
        assertFalse(tx.<Boolean>execute(s -> store.complete(old, SmtpGateway.Result.ACCEPTED)));
        assertTrue(tx.<Boolean>execute(s -> store.complete(current, SmtpGateway.Result.ACCEPTED)));
        assertEquals("ACCEPTED", state(id));
        assertTrue(store.claim().isEmpty());
    }

    @Test
    void expiredSendingBecomesUncertainAndNeverAutomaticallyRequeues() {
        UUID id = enqueue();
        var claim = claim();
        assertTrue(tx.<Boolean>execute(s -> store.beginSending(claim)));
        jdbc.update(
                "UPDATE platform_mail_delivery SET lease_until=now()-interval '1 second' WHERE id=?",
                id);
        assertEquals(1, tx.<Integer>execute(s -> store.recoverExpired()));
        assertEquals("UNCERTAIN", state(id));
        assertFalse(tx.<Boolean>execute(s -> store.complete(claim, SmtpGateway.Result.ACCEPTED)));
        assertTrue(store.claim().isEmpty());
    }

    @Test
    void liveSendingLeaseCanBeRenewedButExpiredLeaseCannot() {
        UUID id = enqueue();
        var claim = claim();
        assertTrue(tx.<Boolean>execute(s -> store.beginSending(claim)));
        assertTrue(tx.<Boolean>execute(s -> store.renewLease(claim)));
        jdbc.update(
                "UPDATE platform_mail_delivery SET lease_until=now()-interval '1 second' WHERE id=?",
                id);
        assertFalse(tx.<Boolean>execute(s -> store.renewLease(claim)));
    }

    @Test
    void transactionStartDoesNotKeepAnExpiredLeaseAlive() {
        UUID id = enqueue();
        var claim = claim();
        tx.executeWithoutResult(
                status -> {
                    jdbc.update(
                            "UPDATE platform_mail_delivery SET lease_until=clock_timestamp()+interval '100 milliseconds' WHERE id=?",
                            id);
                    jdbc.execute("SELECT pg_sleep(0.2)");
                    assertFalse(store.beginSending(claim));
                });
        assertEquals("LEASED", state(id));
    }

    @Test
    void lockWaitCannotExtendSendingRenewalOrCompletionEligibility() throws Exception {
        for (String operation : List.of("send", "renew", "complete")) {
            UUID id = enqueue();
            var claim = claim();
            if (!operation.equals("send"))
                assertTrue(tx.<Boolean>execute(status -> store.beginSending(claim)));
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var blocker =
                        executor.submit(
                                () ->
                                        tx.executeWithoutResult(
                                                status -> {
                                                    jdbc.queryForObject(
                                                            "SELECT id FROM platform_mail_delivery WHERE id=? FOR UPDATE",
                                                            UUID.class,
                                                            id);
                                                    jdbc.update(
                                                            "UPDATE platform_mail_delivery SET lease_until=clock_timestamp()+interval '100 milliseconds' WHERE id=?",
                                                            id);
                                                    locked.countDown();
                                                    try {
                                                        assertTrue(
                                                                release.await(3, TimeUnit.SECONDS));
                                                    } catch (InterruptedException interrupted) {
                                                        throw new IllegalStateException(
                                                                interrupted);
                                                    }
                                                }));
                assertTrue(locked.await(2, TimeUnit.SECONDS));
                var sending =
                        executor.submit(
                                () ->
                                        tx.<Boolean>execute(
                                                status ->
                                                        switch (operation) {
                                                            case "send" ->
                                                                    store.beginSending(claim);
                                                            case "renew" -> store.renewLease(claim);
                                                            default ->
                                                                    store.complete(
                                                                            claim,
                                                                            SmtpGateway.Result
                                                                                    .ACCEPTED);
                                                        }));
                Thread.sleep(250);
                release.countDown();
                blocker.get(3, TimeUnit.SECONDS);
                assertFalse(sending.get(3, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            assertEquals(operation.equals("send") ? "LEASED" : "SENDING", state(id));
        }
    }

    @Test
    void expiredCompletionDoesNotAcknowledgeOrRequeue() {
        for (var result :
                List.of(SmtpGateway.Result.ACCEPTED, SmtpGateway.Result.CONNECTION_FAILED)) {
            UUID id = enqueue();
            var claim = claim();
            assertTrue(tx.<Boolean>execute(status -> store.beginSending(claim)));
            jdbc.update(
                    "UPDATE platform_mail_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",
                    id);
            assertFalse(tx.<Boolean>execute(status -> store.complete(claim, result)));
            assertEquals("SENDING", state(id));
            tx.executeWithoutResult(status -> store.recoverExpired());
            assertEquals("UNCERTAIN", state(id));
        }
        assertTrue(store.claim().isEmpty());
    }

    @Test
    void connectionFailuresBackOffAndStopAtFiveAttempts() {
        UUID id = enqueue();
        for (int attempt = 1; attempt <= 5; attempt++) {
            var claim = claim();
            assertEquals(attempt, claim.attempts());
            assertTrue(tx.<Boolean>execute(s -> store.beginSending(claim)));
            assertTrue(
                    tx.<Boolean>execute(
                            s -> store.complete(claim, SmtpGateway.Result.CONNECTION_FAILED)));
            assertEquals(attempt == 5 ? "FAILED" : "PENDING", state(id));
            assertTrue(store.claim().isEmpty());
            if (attempt < 5)
                jdbc.update(
                        "UPDATE platform_mail_delivery SET available_at=now()-interval '1 second' WHERE id=?",
                        id);
        }
    }

    @Test
    void expiryAuthenticationAndConfigurationFailuresDoNotRetry() {
        UUID expired = enqueue();
        jdbc.update(
                "UPDATE platform_mail_delivery SET expires_at=now()-interval '1 second' WHERE id=?",
                expired);
        assertTrue(store.claim().isEmpty());
        tx.execute(s -> store.recoverExpired());
        assertEquals("EXPIRED", state(expired));
        UUID auth = enqueue();
        var claim = claim();
        tx.execute(s -> store.beginSending(claim));
        tx.execute(s -> store.complete(claim, SmtpGateway.Result.AUTHENTICATION_FAILED));
        assertEquals("FAILED", state(auth));
        UUID config = enqueue();
        var leased = claim();
        assertTrue(tx.<Boolean>execute(s -> store.failBeforeSending(leased)));
        assertEquals("FAILED", state(config));
        assertFalse(tx.<Boolean>execute(s -> store.beginSending(leased)));
        assertTrue(store.claim().isEmpty());
    }

    @Test
    void payloadBoundsAndUncertainCompletionRemainTerminal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.enqueue(UUID.randomUUID(), mail, 86401));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store.enqueue(
                                UUID.randomUUID(),
                                new OutgoingMail(
                                        "fixture@example.test", "Large", "界".repeat(30000)),
                                3600));
        assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM platform_mail_delivery", Integer.class));
        UUID id = enqueue();
        var claim = claim();
        assertFalse(tx.<Boolean>execute(s -> store.complete(claim, SmtpGateway.Result.ACCEPTED)));
        tx.execute(s -> store.beginSending(claim));
        assertTrue(
                tx.<Boolean>execute(
                        s -> store.complete(claim, SmtpGateway.Result.DELIVERY_UNCERTAIN)));
        assertEquals("UNCERTAIN", state(id));
        assertTrue(store.claim().isEmpty());
        assertFalse(tx.<Boolean>execute(s -> store.complete(claim, SmtpGateway.Result.ACCEPTED)));
    }

    @Test
    void expiryRecoveryParticipatesInAuditTransaction() {
        UUID id = enqueue();
        var claim = claim();
        tx.execute(s -> store.beginSending(claim));
        jdbc.update(
                "UPDATE platform_mail_delivery SET lease_until=now()-interval '1 second' WHERE id=?",
                id);
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.execute(
                                s -> {
                                    assertEquals(
                                            List.of(
                                                    new MailDeliveryStore.Recovery(
                                                            id, "UNCERTAIN")),
                                            store.recoverExpiredEntries());
                                    throw new IllegalStateException("Recovery audit failure");
                                }));
        assertEquals("SENDING", state(id));
        assertEquals(
                List.of(new MailDeliveryStore.Recovery(id, "UNCERTAIN")),
                tx.execute(s -> store.recoverExpiredEntries()));
        assertTrue(tx.execute(s -> store.recoverExpiredEntries()).isEmpty());
    }

    @Test
    void retentionDeletesOnlyOldTerminalRows() {
        UUID terminal = enqueue();
        var claim = claim();
        assertTrue(tx.<Boolean>execute(s -> store.beginSending(claim)));
        assertTrue(tx.<Boolean>execute(s -> store.complete(claim, SmtpGateway.Result.ACCEPTED)));
        jdbc.update(
                "UPDATE platform_mail_delivery SET updated_at=now()-interval '31 days' WHERE id=?",
                terminal);
        UUID pending = enqueue();
        assertEquals(1, tx.<Integer>execute(s -> store.purgeTerminal(Duration.ofDays(30), 100)));
        assertTrue(store.status(terminal).isEmpty());
        assertEquals("PENDING", state(pending));
    }
}
