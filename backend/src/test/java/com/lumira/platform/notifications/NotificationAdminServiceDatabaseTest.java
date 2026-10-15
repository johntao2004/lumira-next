package com.lumira.platform.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumira.platform.TestSchema;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.DurableEventConsumerPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.events.PlatformEventOutboxStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class NotificationAdminServiceDatabaseTest {
    private static final String ACTOR = "notification-admin";
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private NotificationAdminService service;
    private NotificationBroadcastEventConsumer broadcastConsumer;
    private String schema;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID disabledUser = UUID.randomUUID();
    private final UUID deletedUser = UUID.randomUUID();
    private final UUID firstRole = UUID.randomUUID();
    private final UUID secondRole = UUID.randomUUID();
    private final UUID emptyRole = UUID.randomUUID();
    private final UUID disabledRole = UUID.randomUUID();
    private String auditedAction;
    private UUID auditedTarget;
    private final List<String> requiredPermissions = new ArrayList<>();

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_notification_admin_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        jdbc.execute(
                "CREATE TABLE platform_user(id UUID PRIMARY KEY,username TEXT UNIQUE,display_name"
                        + " TEXT,enabled BOOLEAN NOT NULL,deleted_at timestamptz)");
        jdbc.execute(TestSchema.table("platform_event_operation"));
        TestSchema.indexes("platform_event_operation").forEach(jdbc::execute);
        jdbc.execute(TestSchema.table("platform_event_outbox"));
        TestSchema.indexes("platform_event_outbox").forEach(jdbc::execute);
        TestSchema.foreignKeys("platform_event_outbox").forEach(jdbc::execute);
        jdbc.execute(TestSchema.table("platform_notification"));
        TestSchema.indexes("platform_notification").forEach(jdbc::execute);
        jdbc.execute(
                "CREATE TABLE platform_role(id UUID PRIMARY KEY,code TEXT NOT NULL,name TEXT NOT"
                        + " NULL,enabled BOOLEAN NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_user_role(user_id UUID NOT NULL,role_id UUID NOT NULL)");

        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,enabled)"
                        + " VALUES(?,?,?,true),(?,?,?,true),(?,?,?,false),(?,?,?,true)",
                alice,
                "alice",
                "Alice",
                bob,
                "bob",
                "Bob",
                disabledUser,
                "disabled",
                "Disabled",
                deletedUser,
                "deleted",
                "Deleted");
        jdbc.update("UPDATE platform_user SET deleted_at=now() WHERE id=?", deletedUser);
        jdbc.update(
                "INSERT INTO platform_role"
                        + " VALUES(?,?,?,true),(?,?,?,true),(?,?,?,true),(?,?,?,false)",
                firstRole,
                "ops",
                "Operations",
                secondRole,
                "support",
                "Support",
                emptyRole,
                "unused",
                "No members",
                disabledRole,
                "retired",
                "Disabled role");
        jdbc.update(
                "INSERT INTO platform_user_role VALUES(?,?),(?,?),(?,?),(?,?),(?,?)",
                alice,
                firstRole,
                alice,
                secondRole,
                bob,
                secondRole,
                disabledUser,
                firstRole,
                alice,
                disabledRole);

        var mapper = new JsonMapper();
        var client = JdbcClient.create(dataSource);
        NotificationStore store = new NotificationStore(client, jdbc, mapper);
        var events = new PlatformEventOutboxStore(client, mapper);
        broadcastConsumer = new NotificationBroadcastEventConsumer(store, mapper);
        AdministrationPort guard =
                new AdministrationPort() {
                    @Override
                    public Set<String> require(String actor, String permission) {
                        requiredPermissions.add("require:" + permission);
                        return Set.of(permission);
                    }

                    @Override
                    public void requireUser(String actor, UUID target, String permission) {}

                    @Override
                    public void requireGlobal(String actor, String permission) {
                        requiredPermissions.add("global:" + permission);
                    }

                    @Override
                    public boolean hasGlobal(String actor, String permission) {
                        return false;
                    }
                };
        AuditPort audit =
                (actor, action, target) -> {
                    auditedAction = action;
                    auditedTarget = target;
                };
        service = new NotificationAdminService(store, client, guard, audit, mapper, events::append);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void returnsOnlyEnabledRoleOptionsAndRequiresGlobalNotificationWrite() {
        var roles = service.enabledRoles(ACTOR);

        assertEquals(3, roles.size());
        assertTrue(roles.stream().allMatch(role -> !role.id().equals(disabledRole)));
        assertEquals(globalNotificationWriteChecks(), requiredPermissions);
    }

    @Test
    void recipientLookupUsesSafePaginationSearchAndOnlyEnabledNonDeletedAccounts() {
        var first = service.recipientOptions(ACTOR, new PageRequest(1, 1, ""));
        assertEquals(2, first.total());
        assertEquals(1, first.items().size());
        assertEquals("alice", first.items().getFirst().username());
        assertTrue(first.items().getFirst().enabled());

        var second = service.recipientOptions(ACTOR, new PageRequest(2, 1, ""));
        assertEquals(2, second.total());
        assertEquals("bob", second.items().getFirst().username());

        var search = service.recipientOptions(ACTOR, new PageRequest(1, 20, "  ALICE  "));
        assertEquals(1, search.total());
        assertEquals("alice", search.items().getFirst().username());

        var wildcard = service.recipientOptions(ACTOR, new PageRequest(1, 20, "%"));
        var injection = service.recipientOptions(ACTOR, new PageRequest(1, 20, "' OR 1=1 --"));
        assertEquals(0, wildcard.total());
        assertEquals(0, injection.total());

        assertEquals("alice", service.recipientOption(ACTOR, alice).username());
        var disabled =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.recipientOption(ACTOR, disabledUser));
        var deleted =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.recipientOption(ACTOR, deletedUser));
        assertEquals("notificationRecipientNotFound", disabled.getReason());
        assertEquals("notificationRecipientNotFound", deleted.getReason());
        List<String> expected = new ArrayList<>();
        for (int call = 0; call < 8; call++) expected.addAll(globalNotificationWriteChecks());
        assertEquals(expected, requiredPermissions);
    }

    @Test
    void roleBroadcastQueuesRecipientSnapshotAndConsumerDeduplicatesDelivery() {
        var published =
                tx.execute(
                        status ->
                                service.publish(
                                        ACTOR,
                                        new NotificationAdminService.Publish(
                                                "ROLES",
                                                null,
                                                List.of(firstRole, secondRole),
                                                "Maintenance",
                                                "Scheduled maintenance",
                                                "  /dashboard/home  ",
                                                null)));

        assertNotNull(published);
        assertEquals(2, published.recipientCount());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_event_outbox WHERE status='PENDING'",
                        Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM platform_notification", Integer.class));

        UUID charlie = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,enabled) VALUES(?,?,?,true)",
                charlie,
                "charlie",
                "Charlie");
        jdbc.update("DELETE FROM platform_user_role WHERE user_id=?", bob);
        jdbc.update(
                "INSERT INTO platform_user_role(user_id,role_id) VALUES(?,?)", charlie, firstRole);
        var event = onlyBroadcastEvent();
        assertTrue(event.payload().contains(alice.toString()));
        assertTrue(event.payload().contains(bob.toString()));
        assertFalse(event.payload().contains(charlie.toString()));
        jdbc.update("UPDATE platform_user SET enabled=false WHERE id=?", bob);
        tx.executeWithoutResult(status -> broadcastConsumer.consume(event));
        tx.executeWithoutResult(status -> broadcastConsumer.consume(event));

        assertEquals(
                1,
                jdbc.queryForObject("SELECT count(*) FROM platform_notification", Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_notification WHERE recipient_id=?",
                        Integer.class,
                        alice));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_notification WHERE recipient_id=?",
                        Integer.class,
                        bob));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_notification WHERE recipient_id=?",
                        Integer.class,
                        charlie));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_notification WHERE href='/dashboard/home'",
                        Integer.class));
        assertEquals("notification.broadcast", auditedAction);
        assertEquals(published.broadcastId(), auditedTarget);
        assertEquals(globalNotificationWriteChecks(), requiredPermissions);
    }

    @Test
    void broadcastChunksStayWithinPayloadLimitAndEnqueueAtMostTenThousandRecipients() {
        List<Object[]> users = new ArrayList<>(499);
        for (int index = 0; index < 499; index++) {
            UUID id = UUID.randomUUID();
            users.add(new Object[] {id, "bulk-" + index, "Bulk " + index, true});
        }
        jdbc.batchUpdate(
                "INSERT INTO platform_user(id,username,display_name,enabled) VALUES(?,?,?,?)",
                users);

        var published =
                service.publish(
                        ACTOR,
                        new NotificationAdminService.Publish(
                                "ALL",
                                null,
                                null,
                                "Maintenance",
                                "Scheduled maintenance",
                                null,
                                null));

        assertEquals(501, published.recipientCount());
        assertEquals(
                2,
                jdbc.queryForObject("SELECT count(*) FROM platform_event_outbox", Integer.class));
        assertTrue(
                jdbc
                        .queryForList(
                                "SELECT octet_length(payload::text) FROM platform_event_outbox",
                                Integer.class)
                        .stream()
                        .allMatch(size -> size <= 262_144));
        for (DurableEventConsumerPort.Event event : broadcastEvents())
            tx.executeWithoutResult(status -> broadcastConsumer.consume(event));
        assertEquals(
                501,
                jdbc.queryForObject("SELECT count(*) FROM platform_notification", Integer.class));
    }

    @Test
    void notificationOutboxWriteRollsBackWithThePublishingTransaction() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.execute(
                                status -> {
                                    service.publish(ACTOR, rolePublish(List.of(firstRole)));
                                    throw new IllegalStateException("rollback");
                                }));

        assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM platform_event_outbox", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM platform_notification", Integer.class));
    }

    @Test
    void oversizedUtf8ParamsAreRejectedBeforeQueueingOrAuditing() {
        String body = "通".repeat(3_000);
        assertEquals(3_000, body.length());
        assertTrue(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8_192);

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.publish(
                                        ACTOR,
                                        new NotificationAdminService.Publish(
                                                "ALL", null, null, "Title", body, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("notificationInvalid", error.getReason());
        assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM platform_event_outbox", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM platform_notification", Integer.class));
        assertEquals(null, auditedAction);
    }

    @Test
    void rejectsDisabledAndUnknownRolesAndAccountsWithoutEnabledMembers() {
        var disabled =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.publish(ACTOR, rolePublish(List.of(disabledRole))));
        assertEquals(HttpStatus.NOT_FOUND, disabled.getStatusCode());
        assertEquals("notificationRoleNotFound", disabled.getReason());

        var missing =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.publish(ACTOR, rolePublish(List.of(UUID.randomUUID()))));
        assertEquals("notificationRoleNotFound", missing.getReason());

        var noMembers =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.publish(ACTOR, rolePublish(List.of(emptyRole))));
        assertEquals(HttpStatus.BAD_REQUEST, noMembers.getStatusCode());
        assertEquals("notificationNoRecipients", noMembers.getReason());
        assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM platform_notification", Integer.class));
    }

    @Test
    void rejectsRoleRecipientsAboveMaximumBeforeCreatingRows() {
        UUID bulkRole = UUID.randomUUID();
        jdbc.update("INSERT INTO platform_role VALUES(?,?,?,true)", bulkRole, "bulk", "Bulk");
        List<Object[]> users = new ArrayList<>(10_001);
        List<Object[]> assignments = new ArrayList<>(10_001);
        for (int i = 0; i < 10_001; i++) {
            UUID userId = UUID.randomUUID();
            users.add(new Object[] {userId, "bulk-" + i, "Bulk user " + i, true});
            assignments.add(new Object[] {userId, bulkRole});
        }
        jdbc.batchUpdate(
                "INSERT INTO platform_user(id,username,display_name,enabled) VALUES(?,?,?,?)",
                users);
        jdbc.batchUpdate("INSERT INTO platform_user_role VALUES(?,?)", assignments);

        var excessive =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                tx.execute(
                                        status ->
                                                service.publish(
                                                        ACTOR, rolePublish(List.of(bulkRole)))));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, excessive.getStatusCode());
        assertEquals("notificationTooManyRecipients", excessive.getReason());
        assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM platform_notification", Integer.class));
    }

    private static NotificationAdminService.Publish rolePublish(List<UUID> roles) {
        return new NotificationAdminService.Publish(
                "ROLES", null, roles, "Title", "Body", null, null);
    }

    private DurableEventConsumerPort.Event onlyBroadcastEvent() {
        List<DurableEventConsumerPort.Event> events = broadcastEvents();
        assertEquals(1, events.size());
        return events.getFirst();
    }

    private List<DurableEventConsumerPort.Event> broadcastEvents() {
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

    private static List<String> globalNotificationWriteChecks() {
        return List.of(
                "require:" + NotificationAdminService.READ,
                "global:" + NotificationAdminService.READ,
                "require:" + NotificationAdminService.CREATE,
                "global:" + NotificationAdminService.CREATE,
                "global:" + NotificationAdminService.READ,
                "global:" + NotificationAdminService.CREATE);
    }
}
