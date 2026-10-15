package com.lumira.platform.events;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.DurableEventConsumerPort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class PlatformEventOutboxWorkerDatabaseTest {
    private static final String SOURCE = "worker-test";
    private static final String TYPE = "worker-test.created";

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private PlatformEventOutboxStore store;
    private TransactionTemplate transaction;
    private String schema;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_event_worker_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        store = new PlatformEventOutboxStore(JdbcClient.create(ds), new JsonMapper());
        for (String table :
                List.of(
                        "platform_event_operation",
                        "platform_event_outbox",
                        "platform_event_trace",
                        "platform_event_replay_approval")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
        for (String table :
                List.of(
                        "platform_event_outbox",
                        "platform_event_trace",
                        "platform_event_replay_approval")) {
            TestSchema.foreignKeys(table).forEach(jdbc::execute);
        }
        jdbc.execute(
                "CREATE TABLE event_consumer_effect(event_id uuid PRIMARY KEY,effect_count integer"
                        + " NOT NULL)");
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null) {
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void consumerExceptionRollsBackDatabaseEffectsBeforeRecordingRetry() {
        var eventId = append("exception");
        var worker =
                worker(
                        event -> {
                            writeEffect(event.id());
                            throw new IllegalStateException("synthetic failure");
                        });
        try {
            worker.process(store.claim().orElseThrow());
        } finally {
            worker.close();
        }

        assertEquals(0, effectCount(eventId));
        assertEquals("PENDING", status(eventId));
        assertEquals("CONSUMER_FAILED", lastError(eventId));
        assertEquals(1, attempts(eventId));
    }

    @Test
    void fencedAcknowledgementRollsBackEffectsAndLeaseRecoveryAllowsOneDelivery() {
        var eventId = append("fenced");
        var firstCall = new AtomicBoolean(true);
        var worker =
                worker(
                        event -> {
                            writeEffect(event.id());
                            if (firstCall.getAndSet(false)) {
                                admin.update(
                                        "UPDATE "
                                                + schema
                                                + ".platform_event_outbox SET"
                                                + " lease_until=clock_timestamp()+interval '200"
                                                + " milliseconds' WHERE id=?",
                                        event.id());
                                Thread.sleep(400);
                            }
                        });
        try {
            var oldClaim = store.claim().orElseThrow();
            worker.process(oldClaim);
            assertEquals(0, effectCount(eventId));
            assertEquals(1, store.recoverExpired().size());

            var recoveredClaim = store.claim().orElseThrow();
            worker.process(recoveredClaim);
            worker.process(oldClaim);

            assertEquals(1, effectCount(eventId));
            assertEquals("DELIVERED", status(eventId));
            assertNull(lastError(eventId));
        } finally {
            worker.close();
        }
    }

    private PlatformEventOutboxWorker worker(EventHandler handler) {
        var consumer =
                new DurableEventConsumerPort() {
                    @Override
                    public String sourceType() {
                        return SOURCE;
                    }

                    @Override
                    public String eventType() {
                        return TYPE;
                    }

                    @Override
                    public void consume(Event event) throws Exception {
                        handler.consume(event);
                    }
                };
        return new PlatformEventOutboxWorker(
                store,
                new PlatformEventConsumerRegistry(List.of(consumer), List.of()),
                transaction);
    }

    private UUID append(String key) {
        UUID id = UUID.randomUUID();
        assertTrue(store.publish(id, SOURCE, TYPE, key, "{\"ok\":true}"));
        return id;
    }

    private void writeEffect(UUID eventId) {
        jdbc.update(
                "INSERT INTO event_consumer_effect(event_id,effect_count) VALUES(?,1) ON"
                        + " CONFLICT(event_id) DO UPDATE SET"
                        + " effect_count=event_consumer_effect.effect_count+1",
                eventId);
    }

    private int effectCount(UUID eventId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COALESCE(MAX(effect_count),0) FROM event_consumer_effect "
                                + "WHERE event_id=?",
                        Integer.class,
                        eventId);
        return count == null ? 0 : count;
    }

    private String status(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT status FROM platform_event_outbox WHERE id=?", String.class, eventId);
    }

    private String lastError(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT last_error FROM platform_event_outbox WHERE id=?", String.class, eventId);
    }

    private int attempts(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT attempts FROM platform_event_outbox WHERE id=?", Integer.class, eventId);
    }

    @FunctionalInterface
    private interface EventHandler {
        void consume(DurableEventConsumerPort.Event event) throws Exception;
    }
}
