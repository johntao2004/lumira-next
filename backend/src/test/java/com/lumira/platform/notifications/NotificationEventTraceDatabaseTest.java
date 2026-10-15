package com.lumira.platform.notifications;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.DurableEventConsumerPort;
import com.lumira.platform.common.DurableEventPublisherPort;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.events.PlatformEventOutboxStore;
import com.lumira.platform.events.PlatformEventTraceQuery;

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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Verifies that a real inbox consumer result is linked to its exact durable event operation. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class NotificationEventTraceDatabaseTest {
    private static final String ACTOR = "qa-notification-trace-admin";
    private static final String PAYLOAD_SECRET = "private-broadcast-body-qa-41f2";

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private NotificationStore notifications;
    private NotificationBroadcastEventConsumer consumer;
    private PlatformEventOutboxStore events;
    private PlatformEventTraceQuery trace;
    private TraceAdministration guard;
    private JsonMapper mapper;
    private String schema;

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_notification_trace_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        mapper = new JsonMapper();
        guard = new TraceAdministration();

        jdbc.execute(
                "CREATE TABLE platform_user(id UUID PRIMARY KEY,username TEXT UNIQUE,display_name"
                        + " TEXT,enabled BOOLEAN NOT NULL,deleted_at timestamptz)");
        for (String table :
                List.of(
                        "platform_audit",
                        "platform_event_operation",
                        "platform_event_outbox",
                        "platform_event_trace",
                        "platform_notification")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
        TestSchema.foreignKeys("platform_event_outbox").forEach(jdbc::execute);
        TestSchema.foreignKeys("platform_event_trace").forEach(jdbc::execute);

        notifications = new NotificationStore(JdbcClient.create(dataSource), jdbc, mapper);
        consumer = new NotificationBroadcastEventConsumer(notifications, mapper);
        events = new PlatformEventOutboxStore(JdbcClient.create(dataSource), mapper);
        trace = new PlatformEventTraceQuery(JdbcClient.create(dataSource), mapper, guard);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void inboxEffectsAndDeliveredTraceCommitTogetherAndDuplicateConsumptionDoesNotDuplicateInbox() {
        UUID activeRecipient = UUID.randomUUID();
        UUID inactiveRecipient = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,enabled)"
                        + " VALUES(?,?,?,true),(?,?,?,false)",
                activeRecipient,
                "active-recipient",
                "Active recipient",
                inactiveRecipient,
                "inactive-recipient",
                "Inactive recipient");

        UUID broadcastId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_audit(id,actor,action,target_id) VALUES(?,?,?,?)",
                auditId,
                ACTOR,
                "notification.broadcast",
                broadcastId);
        events.recordOperation(
                new DurableEventPublisherPort.Operation(
                        operationId,
                        "notification.broadcast",
                        "BROADCAST",
                        broadcastId,
                        null,
                        auditId,
                        1,
                        2,
                        "EVENTS_PENDING"));
        String payload =
                mapper.writeValueAsString(
                        Map.of(
                                "broadcastId",
                                broadcastId,
                                "chunk",
                                0,
                                "recipients",
                                List.of(activeRecipient, inactiveRecipient),
                                "title",
                                "Database trace notice",
                                "body",
                                PAYLOAD_SECRET));
        assertTrue(
                events.publish(
                        eventId,
                        NotificationAdminService.BROADCAST_SOURCE,
                        NotificationAdminService.BROADCAST_EVENT,
                        "broadcast:" + broadcastId + ":0",
                        payload,
                        operationId));

        var claim = events.claim().orElseThrow();
        var claimedEvent = events.event(claim).orElseThrow();
        DurableEventConsumerPort.ConsumptionResult result =
                transaction.execute(
                        status -> {
                            var consumed =
                                    consumer.consumeWithResult(toConsumerEvent(claimedEvent));
                            assertTrue(events.deliver(claim, consumed));
                            return consumed;
                        });

        assertNotNull(result);
        assertTrue(result.known());
        assertEquals(2, result.expectedCount());
        assertEquals(1, result.inboxCreated());
        assertEquals(1, result.inboxSkipped());
        assertEquals(Map.of("RECIPIENT_INACTIVE", 1), result.skippedByReason());
        assertEquals(
                1,
                jdbc.queryForObject("SELECT count(*) FROM platform_notification", Integer.class));
        assertEquals(
                eventId,
                jdbc.queryForObject("SELECT event_id FROM platform_notification", UUID.class));

        var retriedConsumption =
                transaction.execute(
                        status -> consumer.consumeWithResult(toConsumerEvent(claimedEvent)));
        assertNotNull(retriedConsumption);
        assertEquals(0, retriedConsumption.inboxCreated());
        assertEquals(2, retriedConsumption.inboxSkipped());
        assertEquals(
                Map.of("DUPLICATE", 1, "RECIPIENT_INACTIVE", 1),
                retriedConsumption.skippedByReason());
        assertEquals(
                1,
                jdbc.queryForObject("SELECT count(*) FROM platform_notification", Integer.class));

        var operationTrace = trace.operation(ACTOR, auditId, 1, 20);
        assertEquals(1, operationTrace.total());
        assertEquals(eventId, operationTrace.items().getFirst().event().id());
        assertEquals(auditId, operationTrace.operation().auditOperationId());
        assertTrue(operationTrace.operation().result().known());
        assertEquals(2, operationTrace.operation().result().expectedInboxCount());
        assertEquals(1, operationTrace.operation().result().inboxCreated());
        assertEquals(1, operationTrace.operation().result().inboxSkipped());
        assertEquals(
                Map.of("RECIPIENT_INACTIVE", 1),
                operationTrace.operation().result().skippedByReason());

        var eventTrace = trace.event(ACTOR, eventId, 1, 20);
        assertEquals("DELIVERED", eventTrace.event().status());
        assertEquals(1, eventTrace.event().result().inboxCreated());
        assertEquals("SUCCEEDED", eventTrace.history().items().getLast().phase());
        String json = mapper.writeValueAsString(eventTrace);
        assertFalse(json.contains(PAYLOAD_SECRET));
        assertFalse(json.contains("payload"));
        assertFalse(json.contains("lease_token"));
        assertTrue(
                guard.globalPermissions.contains(PlatformPageId.EVENT_OUTBOX.accessPermission()));
        assertTrue(guard.globalPermissions.contains(PlatformPageId.AUDIT.accessPermission()));
    }

    @Test
    void legacyEventWithoutHistoryIsExplicitlyPartialAndDoesNotInventAttempts() {
        UUID eventId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_event_outbox(id,source_type,event_type,event_key,payload"
                        + ") VALUES(?,?,?,?,CAST(? AS jsonb))",
                eventId,
                "notifications",
                "notification.broadcast.requested",
                "legacy:" + eventId,
                "{\"body\":\"" + PAYLOAD_SECRET + "\"}");
        jdbc.update(
                "UPDATE platform_event_outbox SET"
                    + " status='DEAD_LETTER',attempts=8,last_error='CONSUMER_FAILED' WHERE id=?",
                eventId);

        var result = trace.event(ACTOR, eventId, 1, 20);
        assertEquals("PARTIAL", result.historyStatus());
        assertEquals(8, result.event().attempts());
        assertTrue(result.history().items().isEmpty());
        assertEquals(0, result.history().total());
        assertEquals(eventId, result.event().id());
        String json = mapper.writeValueAsString(result);
        assertFalse(json.contains(PAYLOAD_SECRET));
        assertFalse(json.contains("body"));
    }

    private static DurableEventConsumerPort.Event toConsumerEvent(
            PlatformEventOutboxStore.Event event) {
        return new DurableEventConsumerPort.Event(
                event.id(),
                event.sourceType(),
                event.eventType(),
                event.eventKey(),
                event.payload(),
                event.attempts());
    }

    private static final class TraceAdministration implements AdministrationPort {
        private final Set<String> globalPermissions = new HashSet<>();

        @Override
        public Set<String> require(String actor, String permission) {
            return Set.of(permission);
        }

        @Override
        public void requireUser(String actor, UUID target, String permission) {}

        @Override
        public void requireGlobal(String actor, String permission) {
            globalPermissions.add(permission);
        }

        @Override
        public boolean hasGlobal(String actor, String permission) {
            return true;
        }
    }
}
