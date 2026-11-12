package com.lumira.platform.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.TestSchema;
import com.lumira.platform.audit.AuditRepository;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Persistence regressions isolated to a disposable schema on the QA database. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class AiConversationShareServiceDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private String schema;
    private DriverManagerDataSource dataSource;
    private ObjectMapper mapper;
    private AdministrationPort guard;
    private AuditRepository audit;
    private AiConversationShareService service;

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_ai_share_" + java.util.UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        JdbcClient client = JdbcClient.create(dataSource);
        jdbc.execute(TestSchema.table("platform_ai_conversation_share"));
        jdbc.execute(TestSchema.table("platform_audit"));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        mapper = new ObjectMapper();
        guard = mock(AdministrationPort.class);
        when(guard.requirePage("alice", PlatformPageId.AI_CONSOLE)).thenReturn(Set.of());
        when(guard.requireOperation(eq("alice"), any(PlatformOperation.class)))
                .thenReturn(Set.of());
        when(guard.requirePage("bob", PlatformPageId.AI_CONSOLE)).thenReturn(Set.of());
        when(guard.requireOperation(eq("bob"), any(PlatformOperation.class))).thenReturn(Set.of());
        audit = new AuditRepository(client);
        service = new AiConversationShareService(client, mapper, guard, audit);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void snapshotSurvivesServiceRecreationAndOnlyTheHashIsPersisted() throws Exception {
        var input =
                mapper.readTree(
                        "{\"title\":\"Support conversation\",\"messages\":["
                                + "{\"role\":\"user\",\"content\":\"Help with account access\"},"
                                + "{\"role\":\"assistant\",\"content\":\"I can explain the steps. <script>alert(1)</script>\"}]} ");
        var created = transactions.execute(status -> service.create(input, "alice"));
        assertNotNull(created);
        assertEquals(43, created.token().length());
        assertTrue(created.token().matches("[A-Za-z0-9_-]{43}"));
        assertEquals("/share/ai/" + created.token(), created.path());
        assertNotNull(created.createdAt());

        String persistedHash =
                jdbc.queryForObject(
                        "SELECT token_hash FROM platform_ai_conversation_share WHERE id = ("
                                + "SELECT target_id FROM platform_audit WHERE actor='alice' AND action='ai.conversation.shared')",
                        String.class);
        String expectedHash =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                created.token()
                                                        .getBytes(StandardCharsets.US_ASCII)));
        assertEquals(expectedHash, persistedHash);
        assertFalse(persistedHash.equals(created.token()));

        String persisted =
                jdbc.queryForObject(
                        "SELECT title || messages::text || created_by FROM platform_ai_conversation_share",
                        String.class);
        assertFalse(persisted.contains(created.token()));
        assertTrue(persisted.contains("alice"));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_audit WHERE actor='alice' AND action='ai.conversation.shared'",
                        Integer.class));

        var restartedService =
                new AiConversationShareService(JdbcClient.create(dataSource), mapper, guard, audit);
        var restored = restartedService.read(created.token());
        assertEquals("Support conversation", restored.title());
        assertEquals(2, restored.messages().size());
        assertEquals("user", restored.messages().getFirst().role());
        assertEquals("Help with account access", restored.messages().getFirst().content());
        assertEquals("assistant", restored.messages().get(1).role());
        assertEquals(
                "I can explain the steps. <script>alert(1)</script>",
                restored.messages().get(1).content());
        assertEquals(created.createdAt(), restored.createdAt());
    }

    @Test
    void messagesUpdateAtTheSameTokenAndRevocationCannotBeUndoneBySync() throws Exception {
        var created = create("alice", "original");
        var next =
                mapper.readTree(
                        "{\"title\":\"updated\",\"messages\":[{\"role\":\"user\",\"content\":\"text\"},{\"role\":\"assistant\",\"content\":\"new reply\"}]}");
        var foreign =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                transactions.executeWithoutResult(
                                        status -> service.update(created.id(), next, "bob")));
        assertEquals(404, foreign.getStatusCode().value());
        assertEquals("original", service.read(created.token()).title());
        transactions.executeWithoutResult(status -> service.update(created.id(), next, "alice"));
        var restarted =
                new AiConversationShareService(JdbcClient.create(dataSource), mapper, guard, audit);
        assertEquals("updated", restarted.read(created.token()).title());
        assertEquals(2, restarted.read(created.token()).messages().size());
        assertEquals("new reply", restarted.read(created.token()).messages().get(1).content());
        assertEquals(created.createdAt(), restarted.read(created.token()).createdAt());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_ai_conversation_share", Integer.class));
        transactions.executeWithoutResult(status -> service.revoke(created.id(), "alice"));
        var lateSync =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                transactions.executeWithoutResult(
                                        status -> service.update(created.id(), next, "alice")));
        assertEquals(404, lateSync.getStatusCode().value());
        assertEquals(
                404,
                assertThrows(ResponseStatusException.class, () -> restarted.read(created.token()))
                        .getStatusCode()
                        .value());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_ai_conversation_share", Integer.class));
    }

    @Test
    void missingAndMalformedTokensAreNotFound() {
        var missing =
                assertThrows(ResponseStatusException.class, () -> service.read("C".repeat(43)));
        assertEquals(404, missing.getStatusCode().value());
        assertEquals("aiShareNotFound", missing.getReason());

        var malformed =
                assertThrows(ResponseStatusException.class, () -> service.read("bad/token"));
        assertEquals(404, malformed.getStatusCode().value());
        assertEquals("aiShareNotFound", malformed.getReason());
    }

    @Test
    void creatorListIsPagedAndDoesNotExposeOtherCreatorsShares() {
        var older = create("alice", "older");
        var newer = create("alice", "newer");
        create("bob", "private to bob");
        jdbc.update(
                "UPDATE platform_ai_conversation_share SET created_at=? WHERE id=?",
                Timestamp.from(Instant.parse("2026-10-16T00:00:00Z")),
                older.id());
        jdbc.update(
                "UPDATE platform_ai_conversation_share SET created_at=? WHERE id=?",
                Timestamp.from(Instant.parse("2026-10-17T00:00:00Z")),
                newer.id());

        var firstPage = service.list(new PageRequest(1, 1, ""), "alice");
        assertEquals(2, firstPage.total());
        assertEquals(1, firstPage.page());
        assertEquals(1, firstPage.pageSize());
        assertEquals(1, firstPage.items().size());
        assertEquals(newer.id(), firstPage.items().getFirst().id());
        assertEquals("newer", firstPage.items().getFirst().title());

        var secondPage = service.list(new PageRequest(2, 1, ""), "alice");
        assertEquals(older.id(), secondPage.items().getFirst().id());
        assertEquals("older", secondPage.items().getFirst().title());

        var bobPage = service.list(new PageRequest(1, 20, ""), "bob");
        assertEquals(1, bobPage.total());
        assertEquals("private to bob", bobPage.items().getFirst().title());
    }

    @Test
    void onlyTheCreatorCanRevokeAndRevocationRemainsPermanentAfterServiceRecreation() {
        var revoked = create("alice", "revoke this");
        var survives = create("alice", "keep this");
        var bobOwned = create("bob", "bob's share");

        var foreignRevoke =
                assertThrows(
                        ResponseStatusException.class, () -> service.revoke(revoked.id(), "bob"));
        assertEquals(404, foreignRevoke.getStatusCode().value());
        assertEquals("aiShareNotFound", foreignRevoke.getReason());
        assertEquals("revoke this", service.read(revoked.token()).title());

        var ownerCannotRevokeAnotherUser =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.revoke(bobOwned.id(), "alice"));
        assertEquals(404, ownerCannotRevokeAnotherUser.getStatusCode().value());
        assertEquals("bob's share", service.read(bobOwned.token()).title());

        service.revoke(revoked.id(), "alice");
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM platform_audit WHERE actor='alice' AND action='ai.conversation.share-revoked' AND target_id=?",
                        Integer.class,
                        revoked.id()));
        var revokedRead =
                assertThrows(ResponseStatusException.class, () -> service.read(revoked.token()));
        assertEquals(404, revokedRead.getStatusCode().value());

        var restartedService =
                new AiConversationShareService(
                        JdbcClient.create(dataSource),
                        mapper,
                        guard,
                        new AuditRepository(JdbcClient.create(dataSource)));
        var stillRevoked =
                assertThrows(
                        ResponseStatusException.class,
                        () -> restartedService.read(revoked.token()));
        assertEquals(404, stillRevoked.getStatusCode().value());
        assertEquals("keep this", restartedService.read(survives.token()).title());
        assertEquals("bob's share", restartedService.read(bobOwned.token()).title());

        var recreated = create("alice", "new link");
        assertFalse(recreated.token().equals(revoked.token()));
        assertEquals("new link", restartedService.read(recreated.token()).title());
        assertEquals("keep this", restartedService.read(survives.token()).title());
    }

    private AiConversationShareService.CreateResult create(String actor, String title) {
        try {
            var input =
                    mapper.readTree(
                            "{\"title\":\""
                                    + title
                                    + "\",\"messages\":[{\"role\":\"user\",\"content\":\"text\"}]} ");
            return transactions.execute(status -> service.create(input, actor));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
