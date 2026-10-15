package com.lumira.platform.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.DurableEventConsumerPort;
import com.lumira.platform.events.PlatformEventOutboxStore;
import com.lumira.platform.feedback.FeedbackPolicy;
import com.lumira.platform.feedback.FeedbackStore;

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class FeedbackNotificationOutboxDatabaseTest {
    private static final String OWNER = "feedback-owner";
    private static final String HANDLER = "feedback-handler";
    private static final FeedbackPolicy POLICY =
            new FeedbackPolicy(
                    Map.of("OPEN", false, "WORKING", false, "RESOLVED", true),
                    Set.of("LOW", "NORMAL", "HIGH"),
                    Set.of("QUESTION", "BUG", "OTHER"),
                    "OPEN",
                    "NORMAL");

    private final UUID ownerId = UUID.randomUUID();
    private final UUID handlerId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private FeedbackStore feedback;
    private NotificationService notificationService;
    private FeedbackNotificationEventConsumer consumer;
    private FeedbackNotificationAudience feedbackAudience;
    private String schema;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_feedback_notification_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var client = JdbcClient.create(dataSource);
        var mapper = new JsonMapper();

        jdbc.execute(
                "CREATE TABLE platform_user(id UUID PRIMARY KEY,username TEXT UNIQUE,display_name"
                        + " TEXT,enabled BOOLEAN NOT NULL,deleted_at timestamptz)");
        jdbc.execute("CREATE TABLE platform_effective_authority(user_id UUID,authority TEXT)");
        jdbc.execute("CREATE TABLE platform_global_authority(user_id UUID,authority TEXT)");
        jdbc.execute(
                "CREATE TABLE platform_file(id UUID PRIMARY KEY,owner_id UUID,state TEXT,media_type"
                        + " TEXT)");
        jdbc.execute(
                "CREATE TABLE platform_audit(id UUID PRIMARY KEY,actor TEXT,action TEXT,target_id"
                        + " UUID)");
        jdbc.execute(TestSchema.table("platform_event_operation"));
        TestSchema.indexes("platform_event_operation").forEach(jdbc::execute);
        for (String table :
                List.of(
                        "platform_feedback",
                        "platform_feedback_file",
                        "platform_event_outbox",
                        "platform_notification")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
        TestSchema.foreignKeys("platform_event_outbox").forEach(jdbc::execute);
        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,enabled)"
                        + " VALUES(?,?,?,true),(?,?,?,true),(?,?,?,true)",
                ownerId,
                OWNER,
                "Feedback owner",
                handlerId,
                HANDLER,
                "Feedback handler",
                managerId,
                "feedback-manager",
                "Feedback manager");
        jdbc.update(
                "INSERT INTO platform_effective_authority(user_id,authority) VALUES(?,?),(?,?)",
                ownerId,
                "platform:feedback:access",
                ownerId,
                "platform:feedback:read");

        var notificationStore = new NotificationStore(client, jdbc, mapper);
        var events = new PlatformEventOutboxStore(client, mapper);
        feedbackAudience = new FeedbackNotificationAudience(client);
        notificationService =
                new NotificationService(
                        notificationStore, client, mapper, null, events::append, feedbackAudience);
        feedback = new FeedbackStore(client, (actor, action, target) -> {}, notificationService);
        consumer =
                new FeedbackNotificationEventConsumer(notificationStore, mapper, feedbackAudience);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void feedbackMutationAndNotificationEventCommitOrRollbackTogetherThenDeliverIdempotently() {
        assertFalse(
                feedbackAudience.isGlobalManager(ownerId),
                "SELF-scoped access/read grants must not classify a user as a global manager");
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.executeWithoutResult(
                                status -> {
                                    feedback.create(
                                            ownerId,
                                            OWNER,
                                            "Cannot sign in",
                                            "<p>Login fails</p>",
                                            null,
                                            "QUESTION",
                                            POLICY);
                                    throw new IllegalStateException("rollback business and event");
                                }));
        assertEquals(
                0, jdbc.queryForObject("SELECT count(*) FROM platform_feedback", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM platform_event_outbox", Integer.class));

        var created =
                tx.execute(
                        status ->
                                feedback.create(
                                        ownerId,
                                        OWNER,
                                        "Cannot sign in",
                                        "<p>Login fails</p>",
                                        null,
                                        "QUESTION",
                                        POLICY));
        assertEquals(
                1,
                jdbc.queryForObject("SELECT count(*) FROM platform_event_outbox", Integer.class));
        var createdEvent = onlyEvent();
        assertEquals("feedback", createdEvent.sourceType());
        assertEquals("feedback.notification.requested", createdEvent.eventType());
        assertEquals(
                "created",
                new JsonMapper().readTree(createdEvent.payload()).path("action").asText());
        tx.executeWithoutResult(
                status ->
                        notificationService.publishFeedback(
                                ownerId, created.id(), 1, "Cannot sign in", "OPEN", false));
        assertEquals(
                1,
                jdbc.queryForObject("SELECT count(*) FROM platform_event_outbox", Integer.class));

        tx.executeWithoutResult(status -> consumer.consume(createdEvent));
        tx.executeWithoutResult(status -> consumer.consume(createdEvent));
        assertEquals(
                1,
                jdbc.queryForObject("SELECT count(*) FROM platform_notification", Integer.class));
        assertEquals(
                createdEvent.id(),
                jdbc.queryForObject("SELECT event_id FROM platform_notification", UUID.class));

        tx.execute(
                status ->
                        feedback.handle(
                                handlerId, HANDLER, created.id(), 1, "RESOLVED", "Fixed", POLICY));
        List<DurableEventConsumerPort.Event> events = events();
        assertEquals(2, events.size());
        var handledPayload = new JsonMapper().readTree(events.get(1).payload());
        assertEquals("handled", handledPayload.path("action").asText());
        assertEquals("RESOLVED", handledPayload.path("status").asText());
        tx.executeWithoutResult(status -> consumer.consume(events.get(1)));
        tx.executeWithoutResult(status -> consumer.consume(events.get(1)));
        assertEquals(
                2,
                jdbc.queryForObject("SELECT count(*) FROM platform_notification", Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_notification WHERE"
                                + " title_key='notificationFeedbackUpdated'",
                        Integer.class));
    }

    @Test
    void globalFeedbackManagerIsSkippedWhenPublishing() {
        grantGlobalFeedbackManagement(managerId);

        tx.executeWithoutResult(
                status ->
                        notificationService.publishFeedback(
                                managerId,
                                UUID.randomUUID(),
                                1,
                                "Manager-owned feedback",
                                "OPEN",
                                false));

        assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM platform_event_outbox", Integer.class),
                "global feedback managers must not create personal feedback notification events");
    }

    @Test
    void queuedFeedbackEventIsSkippedAfterRecipientBecomesGlobalManager() {
        jdbc.update(
                "INSERT INTO platform_effective_authority(user_id,authority) VALUES(?,?),(?,?)",
                managerId,
                "platform:feedback:access",
                managerId,
                "platform:feedback:read");
        assertFalse(feedbackAudience.isGlobalManager(managerId));
        tx.executeWithoutResult(
                status ->
                        notificationService.publishFeedback(
                                managerId,
                                UUID.randomUUID(),
                                1,
                                "Promoted manager feedback",
                                "OPEN",
                                false));
        var queued = onlyEvent();

        grantGlobalFeedbackManagement(managerId);
        assertEquals(
                1,
                jdbc.queryForObject("SELECT count(*) FROM platform_event_outbox", Integer.class));
        tx.executeWithoutResult(status -> consumer.consume(queued));

        assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM platform_notification", Integer.class),
                "a recipient promoted to a global manager must not receive a queued personal copy");
    }

    private void grantGlobalFeedbackManagement(UUID userId) {
        jdbc.update(
                "INSERT INTO platform_global_authority(user_id,authority) VALUES(?,?),(?,?)",
                userId,
                "platform:feedback:access",
                userId,
                "platform:feedback:read");
    }

    private DurableEventConsumerPort.Event onlyEvent() {
        List<DurableEventConsumerPort.Event> rows = events();
        assertEquals(1, rows.size());
        return rows.getFirst();
    }

    private List<DurableEventConsumerPort.Event> events() {
        return jdbc.query(
                "SELECT id,source_type,event_type,event_key,payload::text,attempts"
                        + " FROM platform_event_outbox ORDER BY event_key",
                (rs, row) ->
                        new DurableEventConsumerPort.Event(
                                rs.getObject("id", UUID.class),
                                rs.getString("source_type"),
                                rs.getString("event_type"),
                                rs.getString("event_key"),
                                rs.getString("payload"),
                                rs.getInt("attempts")));
    }
}
