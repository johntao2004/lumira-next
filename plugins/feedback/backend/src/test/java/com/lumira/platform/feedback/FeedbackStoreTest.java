package com.lumira.platform.feedback;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestSchema;
import com.lumira.platform.audit.AuditRepository;
import com.lumira.platform.common.PageRequest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class FeedbackStoreTest {
    JdbcTemplate admin, jdbc;
    TransactionTemplate tx;
    FeedbackStore store;
    String schema;
    UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
    FeedbackPolicy policy =
            new FeedbackPolicy(
                    Map.of("OPEN", false, "WORKING", false, "DONE", true),
                    Set.of("LOW", "NORMAL", "HIGH"),
                    Set.of("QUESTION", "BUG", "FEATURE", "ACCOUNT", "OTHER"),
                    "OPEN",
                    "NORMAL");

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_feedback_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var client = JdbcClient.create(ds);
        store = new FeedbackStore(client, new AuditRepository(client));
        for (String table :
                List.of(
                        "platform_user",
                        "platform_audit",
                        "platform_file",
                        "platform_feedback",
                        "platform_feedback_file")) {
            jdbc.execute(TestSchema.table(table));
            TestSchema.indexes(table).forEach(jdbc::execute);
        }
        TestSchema.foreignKeys("platform_feedback").forEach(jdbc::execute);
        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,password_hash) "
                        + "VALUES(?,'alice','Alice','unused'),(?,'bob','Bob','unused')",
                alice,
                bob);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    FeedbackStore.Detail create() {
        return tx.execute(
                s ->
                        store.create(
                                alice,
                                "alice",
                                "Title",
                                "<p>Text</p><script>bad()</script>",
                                null,
                                "OTHER",
                                policy));
    }

    FeedbackStore.Detail handle(UUID id, long version, String status, String reply) {
        return tx.execute(s -> store.handle(bob, "bob", id, version, status, reply, policy));
    }

    void rejected(String code, Runnable action) {
        assertEquals(code, assertThrows(ResponseStatusException.class, action::run).getReason());
    }

    @Test
    void preservesOwnerIsolationAndSanitizesBeforePersistence() {
        var row = create();
        assertEquals("<p>Text</p>", row.detailHtml());
        assertEquals("NORMAL", row.priority());
        assertEquals("OPEN", row.status());
        rejected("feedbackNotFound", () -> store.findOwned(bob, row.id()));
        assertThrows(
                NullPointerException.class,
                () -> store.listOwned(null, new PageRequest(1, 20, ""), null, null, null));
        jdbc.update("DELETE FROM platform_user WHERE id=?", alice);
        var recreated = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_user(id,username,display_name,password_hash,enabled) "
                        + "VALUES(?,'alice','Alice','unused',true)",
                recreated);
        rejected("feedbackNotFound", () -> store.findOwned(recreated, row.id()));
        assertNull(store.findForManagement(row.id()).ownerId());
    }

    @Test
    void policyStatusesControlHandlingTimesAndStaleRepliesAreRejected() {
        var row = create();
        var done = handle(row.id(), 1, "DONE", " Fixed ");
        assertEquals(2, done.version());
        assertEquals("Fixed", done.adminReply());
        assertNotNull(done.handledAt());
        assertEquals(bob, done.handledBy());
        rejected("staleRecord", () -> handle(row.id(), 1, "DONE", "overwrite"));
        var reopened = handle(row.id(), 2, "WORKING", null);
        assertNull(reopened.handledAt());
        assertNull(reopened.adminReply());
        rejected("invalidFeedback", () -> handle(row.id(), 3, "UNKNOWN", "reply"));
        assertEquals(3, store.findForManagement(row.id()).version());
    }

    @Test
    void paginatesOwnMetadataAndFiltersWithoutTruncation() {
        for (int i = 0; i < 25; i++) create();
        tx.execute(s -> store.create(bob, "bob", "Other", "text", "HIGH", "BUG", policy));
        var first =
                store.listOwned(alice, new PageRequest(1, 20, "title"), "OPEN", "NORMAL", "OTHER");
        var last =
                store.listOwned(alice, new PageRequest(2, 20, "title"), "OPEN", "NORMAL", "OTHER");
        assertEquals(25, first.total());
        assertEquals(20, first.items().size());
        assertEquals(5, last.items().size());
        var ids = new HashSet<UUID>();
        first.items().forEach(r -> ids.add(r.id()));
        last.items().forEach(r -> ids.add(r.id()));
        assertEquals(25, ids.size());
        assertEquals(
                26, store.listForManagement(new PageRequest(1, 20, ""), null, null, null).total());
        assertEquals(
                1,
                store.listForManagement(new PageRequest(1, 20, "Other"), null, "HIGH", null)
                        .total());
    }

    @Test
    void auditFailureRollsBackCreateAndHandling() {
        jdbc.execute(
                "ALTER TABLE platform_audit ADD CONSTRAINT reject_create CHECK(action<>'feedback.created')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, this::create);
        assertEquals(
                0, jdbc.queryForObject("SELECT count(*) FROM platform_feedback", Integer.class));
        jdbc.execute("ALTER TABLE platform_audit DROP CONSTRAINT reject_create");
        var row = create();
        jdbc.execute(
                "ALTER TABLE platform_audit ADD CONSTRAINT reject_handle CHECK(action<>'feedback.handled')");
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> handle(row.id(), 1, "DONE", "fixed"));
        assertEquals(row, store.findOwned(alice, row.id()));
    }

    @Test
    void bindsOnlyReadyNormalizedImagesOwnedByAuthor() {
        UUID file = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_file(id,owner_id,storage_backend,original_name,state,size_bytes,sha256,media_type) "
                        + "VALUES(?,?,'local','image.png','READY',1,repeat('a',64),?)",
                file,
                alice,
                "image/png");
        var row =
                tx.execute(
                        s ->
                                store.create(
                                        alice,
                                        "alice",
                                        "Image",
                                        "<p>Screenshot</p><img src='/api/feedback/images/"
                                                + file
                                                + "' alt='screen'>",
                                        null,
                                        "OTHER",
                                        policy));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_feedback_file WHERE feedback_id=? AND file_id=?",
                        Integer.class,
                        row.id(),
                        file));
        UUID other = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_file(id,owner_id,storage_backend,original_name,state,size_bytes,sha256,media_type) "
                        + "VALUES(?,?,'local','other.bin','READY',1,repeat('b',64),?)",
                other,
                bob,
                "application/octet-stream");
        rejected(
                "invalidFeedback",
                () ->
                        tx.execute(
                                s ->
                                        store.create(
                                                alice,
                                                "alice",
                                                "Invalid",
                                                "<img src='/api/feedback/images/" + other + "'>",
                                                null,
                                                "OTHER",
                                                policy)));
    }

    @Test
    void concurrentReplyEditsHaveOneWinnerAndDisabledActorsCannotWrite() throws Exception {
        var row = create();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt =
                    () -> {
                        start.await();
                        try {
                            handle(row.id(), 1, "WORKING", "Reply");
                            return true;
                        } catch (ResponseStatusException e) {
                            assertEquals("staleRecord", e.getReason());
                            return false;
                        }
                    };
            var a = pool.submit(attempt);
            var b = pool.submit(attempt);
            start.countDown();
            assertNotEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
        jdbc.update("UPDATE platform_user SET enabled=false WHERE id=?", bob);
        rejected("unauthorized", () -> handle(row.id(), 2, "DONE", "fixed"));
        rejected(
                "unauthorized",
                () ->
                        tx.execute(
                                s ->
                                        store.create(
                                                alice, "bob", "Title", "text", null, "OTHER",
                                                policy)));
    }
}
