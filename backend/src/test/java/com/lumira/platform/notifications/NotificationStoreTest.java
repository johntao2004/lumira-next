package com.lumira.platform.notifications;

import com.lumira.platform.TestMigration;

import com.lumira.platform.common.PageRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class NotificationStoreTest {
    JdbcTemplate admin, jdbc;
    TransactionTemplate tx;
    NotificationStore store;
    String schema;
    UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL"), user = System.getenv("LUMIRA_TEST_DB_USER"), password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_notifications_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        store = new NotificationStore(JdbcClient.create(ds), new JdbcTemplate(ds), new JsonMapper());
        jdbc.execute("CREATE TABLE platform_user(id UUID PRIMARY KEY,username TEXT UNIQUE,enabled BOOLEAN)");
        jdbc.execute(TestMigration.read("V39__platform_event_outbox.sql"));
        jdbc.execute(TestMigration.read("V40__platform_notifications.sql"));
        // V41 widened the message-key pattern to the camelCase catalogue keys used by broadcasts.
        jdbc.execute(TestMigration.read("V41__notification_message_key_case.sql"));
        jdbc.update("INSERT INTO platform_user VALUES(?,'alice',true),(?,'bob',true)", alice, bob);
    }

    @AfterEach
    void cleanup() { if (admin != null && schema != null) admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }

    private boolean create(UUID recipient, String key) { return tx.execute(s -> store.create(recipient, null, key, "feedback", "title", "body", "{\"title\":\"Hello\"}", "/dashboard/feedback")); }

    @Test
    void deduplicatesAndIsolatesInboxRows() {
        assertTrue(create(alice, "feedback:1:created"));
        assertFalse(create(alice, "feedback:1:created"));
        assertTrue(create(bob, "feedback:1:created"));
        assertEquals(1, store.unreadCount(alice));
        assertEquals(1, store.unreadCount(bob));
        var row = store.list(alice, new PageRequest(1, 20, ""), false).items().getFirst();
        assertEquals("feedback", row.kind());
        assertEquals("title", row.titleKey());
        assertFalse(row.read());
        assertEquals("/dashboard/feedback", row.href());
    }

    @Test
    void paginatesUnreadAndMarksOnlyOwnedRows() {
        for (int i = 0; i < 25; i++) create(alice, "feedback:" + i + ":created");
        var first = store.list(alice, new PageRequest(1, 20, ""), true);
        var second = store.list(alice, new PageRequest(2, 20, ""), true);
        assertEquals(25, first.total()); assertEquals(20, first.items().size()); assertEquals(5, second.items().size());
        UUID own = first.items().getFirst().id(), foreign = UUID.randomUUID();
        assertFalse(store.markRead(bob, own));
        assertTrue(store.markRead(alice, own)); assertFalse(store.markRead(alice, own));
        assertEquals(24, store.unreadCount(alice));
        assertEquals(24, store.list(alice, new PageRequest(1, 20, ""), true).total());
        assertEquals(24, store.markAllRead(alice)); assertEquals(0, store.unreadCount(alice));
        assertFalse(store.markRead(alice, foreign));
    }

    @Test
    void excludesExpiredEntriesAndRejectsInvalidPayloadOrKeys() {
        assertTrue(create(alice, "feedback:valid:created"));
        jdbc.update("UPDATE platform_notification SET expires_at=now()-interval '1 second' WHERE recipient_id=?", alice);
        assertEquals(0, store.unreadCount(alice));
        assertThrows(IllegalArgumentException.class, () -> store.create(alice, null, "bad key", "feedback", "title", "body", "{}", "/dashboard/feedback"));
        assertThrows(IllegalArgumentException.class, () -> store.create(alice, null, "feedback:bad", "feedback", "title", "body", "not-json", "/dashboard/feedback"));
        assertThrows(IllegalArgumentException.class, () -> store.create(alice, null, "feedback:link", "feedback", "title", "body", "{}", "https://example.com"));
    }

    @Test
    void batchCreationKeepsPerRecipientDeduplication() {
        // Broadcasts are not backed by an outbox row, so event_id stays NULL (as NotificationAdminService does).
        UUID event = UUID.randomUUID();
        var requests = List.of(
            new NotificationStore.CreateRequest(alice, null, "broadcast:" + event + ":" + alice,
                "broadcast", "notificationCustomTitle", "notificationCustomBody", "{\"title\":\"Hello\"}", "/dashboard/feedback", null),
            new NotificationStore.CreateRequest(bob, null, "broadcast:" + event + ":" + bob,
                "broadcast", "notificationCustomTitle", "notificationCustomBody", "{\"title\":\"Hello\"}", "/dashboard/feedback", null)
        );
        assertEquals(Integer.valueOf(2), tx.execute(status -> store.createBatch(requests)));
        assertEquals(Integer.valueOf(0), tx.execute(status -> store.createBatch(requests)));
        assertEquals(1, store.unreadCount(alice));
        assertEquals(1, store.unreadCount(bob));
    }

    @Test
    void duplicateCreationHasOneWinnerAcrossWorkers() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> { start.await(); return create(alice, "feedback:concurrent:created"); };
            var a = pool.submit(attempt); var b = pool.submit(attempt); start.countDown();
            assertNotEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM platform_notification", Integer.class));
    }
}
