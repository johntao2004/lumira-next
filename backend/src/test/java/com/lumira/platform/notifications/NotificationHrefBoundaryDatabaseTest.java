package com.lumira.platform.notifications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.TestMigration;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class NotificationHrefBoundaryDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private NotificationStore store;
    private NotificationAdminService service;
    private String schema;
    private final UUID recipient = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_notification_href_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        var client = JdbcClient.create(dataSource);
        var json = new JsonMapper();
        jdbc.execute(
                "CREATE TABLE platform_user(id uuid PRIMARY KEY,username text UNIQUE,enabled boolean,display_name text DEFAULT 'Recipient')");
        jdbc.execute(TestMigration.read("V39__platform_event_outbox.sql"));
        jdbc.execute(TestMigration.read("V40__platform_notifications.sql"));
        jdbc.execute(TestMigration.read("V41__notification_message_key_case.sql"));
        jdbc.update(
                "INSERT INTO platform_user(id,username,enabled) VALUES(?,'recipient',true)",
                recipient);
        store = new NotificationStore(client, jdbc, json);
        service =
                new NotificationAdminService(
                        store, client, mock(AdministrationPort.class), mock(AuditPort.class), json);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void rejectsAmbiguousLinksAtPublisherAndBothStoreWrites() {
        for (String href :
                List.of(
                        "/\\outside.invalid/check",
                        "/\t/outside.invalid/check",
                        "/\r/outside.invalid/check",
                        "/\n/outside.invalid/check",
                        "/folder\\child",
                        "//outside.invalid/check",
                        "https://outside.invalid/check")) {
            var error = assertThrows(ResponseStatusException.class, () -> publish(href));
            assertEquals(400, error.getStatusCode().value());
            assertEquals("notificationInvalid", error.getReason());
            assertEquals(0, count());

            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            store.create(
                                    recipient,
                                    null,
                                    "single:" + key(href),
                                    "feedback",
                                    "title",
                                    "body",
                                    "{}",
                                    href));
            assertEquals(0, count());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> store.createBatch(List.of(request("batch:" + key(href), href))));
            assertEquals(0, count());
        }
    }

    @Test
    void preservesLegitimateDestinationsAndSuppressesUnsafeHistoricalLinksInBothViews() {
        for (String href :
                List.of(
                        "/",
                        "/dashboard/home?x=1#section",
                        "/dashboard/feedback?name=中文",
                        "/dashboard/home?text=%5C",
                        "/dashboard/home?q=\\value",
                        "/dashboard/home#\\fragment",
                        "/%2F%5C%09safe",
                        "/dashboard/home?next=https%3A%2F%2Fexample.invalid")) {
            assertEquals(1, publish(href).recipientCount());
            assertEquals(
                    href,
                    store.list(recipient, new PageRequest(1, 50, ""), false)
                            .items()
                            .getFirst()
                            .href());
            jdbc.update("DELETE FROM platform_notification");
        }

        for (String unsafe : List.of("/\\outside.invalid/check", "/\t/outside.invalid/check"))
            jdbc.update(
                    "INSERT INTO platform_notification(id,recipient_id,dedupe_key,kind,title_key,body_key,params,href) VALUES(?,?,'legacy:' || ?,'feedback','title','body','{}',?)",
                    UUID.randomUUID(),
                    recipient,
                    key(unsafe),
                    unsafe);
        var inbox = store.list(recipient, new PageRequest(1, 10, ""), false).items();
        var adminRows = store.listAdmin(new PageRequest(1, 10, "")).items();
        assertEquals(2, inbox.size());
        assertEquals(2, adminRows.size());
        assertTrue(inbox.stream().allMatch(row -> row.href() == null && !row.read()));
        assertTrue(adminRows.stream().allMatch(row -> row.href() == null));
        assertTrue(inbox.stream().allMatch(row -> row.titleKey().equals("title")));
        assertEquals(2, count());
    }

    private NotificationAdminService.Published publish(String href) {
        return service.publish(
                "publisher",
                new NotificationAdminService.Publish(
                        "USERS",
                        List.of(recipient),
                        List.of(),
                        "Fixture title",
                        "Fixture body",
                        href,
                        null));
    }

    private NotificationStore.CreateRequest request(String key, String href) {
        return new NotificationStore.CreateRequest(
                recipient, null, key, "feedback", "title", "body", "{}", href, null);
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM platform_notification", Integer.class);
    }

    private static String key(String value) {
        return Integer.toUnsignedString(value.hashCode());
    }
}
