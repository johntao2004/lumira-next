package com.lumira.platform.events;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestMigration;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class PlatformEventOutboxStoreTest {
    JdbcTemplate admin, jdbc;
    TransactionTemplate tx;
    PlatformEventOutboxStore store;
    String schema;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_event_outbox_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        store = new PlatformEventOutboxStore(JdbcClient.create(ds), new JsonMapper());
        jdbc.execute(TestMigration.read("V39__platform_event_outbox.sql"));
        jdbc.execute(
                """
                CREATE TABLE platform_event_replay_approval(
                    id uuid PRIMARY KEY,
                    event_id uuid NOT NULL REFERENCES platform_event_outbox(id) ON DELETE RESTRICT,
                    approved_by varchar(100) NOT NULL CHECK (length(trim(approved_by)) > 0),
                    reason varchar(1000) NOT NULL CHECK (length(trim(reason)) > 0),
                    created_at timestamptz NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute(
                "CREATE INDEX platform_event_replay_approval_event ON platform_event_replay_approval(event_id,created_at DESC,id DESC)");
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void appendsCanonicalJsonAndIsIdempotentAcrossIdOrEventKey() {
        UUID id = UUID.randomUUID();
        assertTrue(
                store.append(
                        id,
                        "feedback",
                        "feedback.created",
                        "feedback-1",
                        "{ \"title\": \"x\", \"n\": 1 }"));
        assertFalse(
                store.append(id, "feedback", "feedback.created", "different", "{\"other\":true}"));
        assertFalse(
                store.append(
                        UUID.randomUUID(),
                        "feedback",
                        "feedback.created",
                        "feedback-1",
                        "{\"other\":true}"));
        assertEquals(
                1,
                jdbc.queryForObject("SELECT count(*) FROM platform_event_outbox", Integer.class));
        var claim = store.claim().orElseThrow();
        var event = store.event(claim).orElseThrow();
        assertEquals(id, event.id());
        var canonical = new JsonMapper().readTree(event.payload());
        assertEquals(1, canonical.get("n").asInt());
        assertEquals("x", canonical.get("title").asText());
    }

    @Test
    void validatesBoundsAndRejectsNullOrControlKeys() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.append(UUID.randomUUID(), "Bad", "event", "key", "{}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.append(UUID.randomUUID(), "source", "event", "bad\nkey", "{}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.append(UUID.randomUUID(), "source", "event", "key", "null"));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.append(UUID.randomUUID(), "source", "event", "key", "not-json"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store.append(
                                UUID.randomUUID(),
                                "source",
                                "event",
                                "key",
                                "{\"v\":\"" + "x".repeat(262145) + "\"}"));
    }

    @Test
    void claimAndDeliveryFenceOldTokensAndRecoverExpiredLeases() {
        UUID id = UUID.randomUUID();
        store.append(id, "source", "event", "key", "{\"ok\":true}");
        var claim = store.claim().orElseThrow();
        assertFalse(
                store.deliver(
                        new PlatformEventOutboxStore.Claim(
                                id, UUID.randomUUID(), claim.attempts())));
        assertTrue(store.deliver(claim));
        assertFalse(store.deliver(claim));
        assertEquals(
                "DELIVERED",
                jdbc.queryForObject(
                        "SELECT status FROM platform_event_outbox WHERE id=?", String.class, id));

        UUID expired = UUID.randomUUID();
        store.append(expired, "source", "event", "expired", "{\"ok\":true}");
        var expiredClaim = store.claim().orElseThrow();
        jdbc.update(
                "UPDATE platform_event_outbox SET lease_until=now()-interval '1 second' WHERE id=?",
                expired);
        var recovered = store.recoverExpired();
        assertEquals(1, recovered.size());
        assertEquals("PENDING", recovered.getFirst().status());
        assertFalse(store.event(expiredClaim).isPresent());
    }

    @Test
    void failuresBackoffAndBecomeDeadLetterAtAttemptLimit() {
        UUID id = UUID.randomUUID();
        store.append(id, "source", "event", "retry", "{\"ok\":true}");
        var claim = store.claim().orElseThrow();
        assertTrue(store.fail(claim, "temporary transport error"));
        assertEquals(
                "PENDING",
                jdbc.queryForObject(
                        "SELECT status FROM platform_event_outbox WHERE id=?", String.class, id));
        jdbc.update(
                "UPDATE platform_event_outbox SET available_at=now(),attempts=7 WHERE id=?", id);
        var last = store.claim().orElseThrow();
        assertTrue(store.fail(last, "final failure"));
        assertEquals(
                "DEAD_LETTER",
                jdbc.queryForObject(
                        "SELECT status FROM platform_event_outbox WHERE id=?", String.class, id));
    }

    @Test
    void replayResetsOnlyDeadLettersAndFencesOtherStates() {
        UUID id = UUID.randomUUID();
        store.append(id, "source", "event", "replay", "{\"ok\":true}");
        jdbc.update(
                "UPDATE platform_event_outbox SET status='DEAD_LETTER',attempts=8,last_error='CONSUMER_FAILED',delivered_at=NULL WHERE id=?",
                id);
        var replay = store.replay(id);
        assertEquals(new PlatformEventOutboxStore.Replay(id, "PENDING", 0), replay);
        assertEquals(
                "PENDING",
                jdbc.queryForObject(
                        "SELECT status FROM platform_event_outbox WHERE id=?", String.class, id));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT attempts FROM platform_event_outbox WHERE id=?",
                        Integer.class,
                        id));
        assertNull(
                jdbc.queryForObject(
                        "SELECT last_error FROM platform_event_outbox WHERE id=?",
                        String.class,
                        id));
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> store.replay(id));
    }

    @Test
    void concurrentWorkersClaimOneEvent() throws Exception {
        UUID id = UUID.randomUUID();
        store.append(id, "source", "event", "concurrent", "{\"ok\":true}");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Optional<PlatformEventOutboxStore.Claim>> attempt =
                    () -> {
                        start.await();
                        return store.claim();
                    };
            var a = pool.submit(attempt);
            var b = pool.submit(attempt);
            start.countDown();
            var first = a.get(15, TimeUnit.SECONDS);
            var second = b.get(15, TimeUnit.SECONDS);
            assertTrue(first.isPresent() ^ second.isPresent());
        }
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_event_outbox WHERE status='PROCESSING'",
                        Integer.class));
    }

    @Test
    void retentionDeletesOnlyOldDeliveredRowsInBoundedBatches() {
        UUID delivered = UUID.randomUUID();
        store.append(delivered, "source", "event", "delivered", "{\"ok\":true}");
        var claim = store.claim().orElseThrow();
        assertTrue(store.deliver(claim));
        jdbc.update(
                "UPDATE platform_event_outbox SET updated_at=now()-interval '31 days' WHERE id=?",
                delivered);

        UUID pending = UUID.randomUUID();
        store.append(pending, "source", "event", "pending", "{\"ok\":true}");
        jdbc.update(
                "UPDATE platform_event_outbox SET updated_at=now()-interval '31 days' WHERE id=?",
                pending);

        UUID deadLetter = UUID.randomUUID();
        store.append(deadLetter, "source", "event", "dead", "{\"ok\":true}");
        jdbc.update(
                "UPDATE platform_event_outbox SET status='DEAD_LETTER',attempts=8,updated_at=now()-interval '31 days' WHERE id=?",
                deadLetter);

        assertEquals(1, store.purgeDelivered(Duration.ofDays(30), 1));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_event_outbox WHERE id=?",
                        Integer.class,
                        delivered));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_event_outbox WHERE id=?",
                        Integer.class,
                        pending));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_event_outbox WHERE id=?",
                        Integer.class,
                        deadLetter));
        assertEquals(0, store.purgeDelivered(Duration.ofDays(30), 10));
    }

    @Test
    void approvalReferencedDeliveredRowDoesNotBlockBoundedRetentionBatch() {
        UUID referenced = UUID.randomUUID();
        store.append(referenced, "source", "event", "approved", "{\"proof\":true}");
        jdbc.update(
                "UPDATE platform_event_outbox SET status='DELIVERED',delivered_at=now(),updated_at=now()-interval '31 days' WHERE id=?",
                referenced);
        jdbc.update(
                "INSERT INTO platform_event_replay_approval(id,event_id,approved_by,reason) VALUES(?,?,?,?)",
                UUID.randomUUID(),
                referenced,
                UUID.randomUUID().toString(),
                "synthetic approval proof");

        UUID ordinary = UUID.randomUUID();
        store.append(ordinary, "source", "event", "ordinary", "{\"ok\":true}");
        jdbc.update(
                "UPDATE platform_event_outbox SET status='DELIVERED',delivered_at=now(),updated_at=now()-interval '31 days' WHERE id=?",
                ordinary);

        assertEquals(1, store.purgeDelivered(Duration.ofDays(30), 10));
        assertEquals(
                "true",
                jdbc.queryForObject(
                        "SELECT payload->>'proof' FROM platform_event_outbox WHERE id=?",
                        String.class,
                        referenced));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_event_replay_approval WHERE event_id=?",
                        Integer.class,
                        referenced));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_event_outbox WHERE id=?",
                        Integer.class,
                        ordinary));
    }
}
