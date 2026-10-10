package com.lumira.platform.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumira.platform.TestMigration;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
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
                "CREATE TABLE platform_user(id UUID PRIMARY KEY,username TEXT UNIQUE,display_name TEXT,enabled BOOLEAN NOT NULL,deleted_at timestamptz)");
        jdbc.execute(TestMigration.read("V39__platform_event_outbox.sql"));
        jdbc.execute(TestMigration.read("V40__platform_notifications.sql"));
        jdbc.execute(TestMigration.read("V41__notification_message_key_case.sql"));
        jdbc.execute(
                "CREATE TABLE platform_role(id UUID PRIMARY KEY,code TEXT NOT NULL,name TEXT NOT NULL,enabled BOOLEAN NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_user_role(user_id UUID NOT NULL,role_id UUID NOT NULL)");

        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,enabled) VALUES(?,?,?,true),(?,?,?,true),(?,?,?,false),(?,?,?,true)",
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
                "INSERT INTO platform_role VALUES(?,?,?,true),(?,?,?,true),(?,?,?,true),(?,?,?,false)",
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

        NotificationStore store =
                new NotificationStore(JdbcClient.create(dataSource), jdbc, new JsonMapper());
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
        service =
                new NotificationAdminService(
                        store, JdbcClient.create(dataSource), guard, audit, new JsonMapper());
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
        assertEquals(
                List.of(
                        "require:" + NotificationAdminService.WRITE,
                        "global:" + NotificationAdminService.WRITE),
                requiredPermissions);
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
        assertEquals(16, requiredPermissions.size());
        for (int index = 0; index < requiredPermissions.size(); index += 2) {
            assertEquals(
                    "require:" + NotificationAdminService.WRITE, requiredPermissions.get(index));
            assertEquals(
                    "global:" + NotificationAdminService.WRITE, requiredPermissions.get(index + 1));
        }
    }

    @Test
    void roleBroadcastCreatesOneNotificationPerEnabledAccountAcrossSelectedRoles() {
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
                2,
                jdbc.queryForObject("SELECT count(*) FROM platform_notification", Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_notification WHERE recipient_id=?",
                        Integer.class,
                        alice));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_notification WHERE recipient_id=?",
                        Integer.class,
                        bob));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_notification WHERE href='/dashboard/home'",
                        Integer.class));
        assertEquals("notification.broadcast", auditedAction);
        assertEquals(published.broadcastId(), auditedTarget);
        assertEquals(
                List.of(
                        "require:" + NotificationAdminService.WRITE,
                        "global:" + NotificationAdminService.WRITE),
                requiredPermissions);
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
}
