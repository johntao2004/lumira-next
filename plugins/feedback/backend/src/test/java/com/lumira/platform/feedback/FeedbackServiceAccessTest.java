package com.lumira.platform.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.TestMigration;
import com.lumira.platform.audit.AuditRepository;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.FileContentPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.identity.AdministrationGuard;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

/** Exercises the service boundary directly, including principals without global feedback scope. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class FeedbackServiceAccessTest {
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID scopedManager = UUID.randomUUID();
    private final UUID administrator = UUID.randomUUID();
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private FeedbackStore store;
    private FeedbackService service;
    private FileContentPort files;
    private String schema;
    private final FeedbackPolicy policy =
            new FeedbackPolicy(
                    Map.of("OPEN", false, "PROCESSING", false, "RESOLVED", true),
                    Set.of("LOW", "NORMAL", "HIGH"),
                    Set.of("QUESTION", "BUG", "FEATURE", "ACCOUNT", "OTHER"),
                    "OPEN",
                    "NORMAL");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_feedback_access_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);

        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var client = JdbcClient.create(dataSource);

        jdbc.execute(
                "CREATE TABLE platform_user(id UUID PRIMARY KEY, username TEXT UNIQUE, display_name TEXT, enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE platform_effective_authority(user_id UUID, authority TEXT)");
        jdbc.execute("CREATE TABLE platform_authority(user_id UUID, authority TEXT)");
        jdbc.execute("CREATE TABLE platform_user_role(user_id UUID, role_id UUID)");
        jdbc.execute(
                "CREATE TABLE platform_role(id UUID PRIMARY KEY, enabled BOOLEAN, data_scope TEXT)");
        jdbc.execute("CREATE TABLE platform_role_authority(role_id UUID, authority TEXT)");
        jdbc.execute(
                "CREATE TABLE platform_audit(id UUID PRIMARY KEY, actor TEXT, action TEXT, target_id UUID)");
        jdbc.execute(
                "CREATE TABLE platform_file(id UUID PRIMARY KEY, owner_id UUID, state TEXT, media_type TEXT)");
        jdbc.execute(TestMigration.read("V34__feedback_storage.sql"));
        jdbc.execute("ALTER TABLE platform_feedback ADD COLUMN category VARCHAR(100)");
        jdbc.execute(TestMigration.read("V38__feedback_file_references.sql"));

        addUser(alice, "alice");
        addUser(bob, "bob");
        addUser(scopedManager, "scoped-manager");
        addUser(administrator, "administrator");
        addAuthority(alice, FeedbackService.READ);
        addAuthority(bob, FeedbackService.READ);
        addAuthority(scopedManager, FeedbackService.READ, FeedbackService.MANAGE);
        addAuthority(administrator, FeedbackService.READ, FeedbackService.MANAGE);

        UUID selfRole = UUID.randomUUID();
        UUID allRole = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_role VALUES (?, true, 'SELF'), (?, true, 'ALL')",
                selfRole,
                allRole);
        jdbc.update(
                "INSERT INTO platform_role_authority VALUES (?, ?), (?, ?)",
                selfRole,
                FeedbackService.MANAGE,
                allRole,
                FeedbackService.MANAGE);
        jdbc.update(
                "INSERT INTO platform_user_role VALUES (?, ?), (?, ?)",
                scopedManager,
                selfRole,
                administrator,
                allRole);

        store = new FeedbackStore(client, new AuditRepository(client));
        var catalog = mock(FeedbackCatalog.class);
        when(catalog.load())
                .thenReturn(new FeedbackCatalog.Catalog(policy, List.of(), List.of(), List.of()));
        files = mock(FileContentPort.class);
        when(files.findReadyImageForContent(anyString(), any()))
                .thenThrow(
                        new ResponseStatusException(
                                org.springframework.http.HttpStatus.NOT_FOUND, "fileNotFound"));
        when(files.findManagedFeedbackImageForContent(any()))
                .thenAnswer(invocation -> new FileContentPort.Image(invocation.getArgument(0), 32));
        AdministrationPort guard = new AdministrationGuard(client);
        service = new FeedbackService(store, catalog, guard, client, files);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private void addUser(UUID id, String username) {
        jdbc.update(
                "INSERT INTO platform_user(id, username, display_name, enabled) VALUES (?, ?, ?, true)",
                id,
                username,
                username);
    }

    private void addAuthority(UUID userId, String... authorities) {
        for (String authority : authorities) {
            jdbc.update(
                    "INSERT INTO platform_effective_authority(user_id, authority) VALUES (?, ?)",
                    userId,
                    authority);
        }
    }

    private FeedbackStore.Detail create(UUID owner, String username, String title) {
        return tx.execute(
                status ->
                        store.create(
                                owner,
                                username,
                                title,
                                "<p>Details</p>",
                                "NORMAL",
                                "OTHER",
                                policy));
    }

    private static void assertDenied(String code, Runnable action) {
        assertEquals(code, assertThrows(ResponseStatusException.class, action::run).getReason());
    }

    @Test
    void directOwnerReadsStayScopedAndSelfScopedManagersCannotReadOrHandleOthers() {
        var aliceRow = create(alice, "alice", "Alice feedback");
        var bobRow = create(bob, "bob", "Bob feedback");

        var mine = service.mine("alice", new PageRequest(1, 20, ""), "", "", "");
        assertEquals(1, mine.total());
        assertEquals(aliceRow.id(), mine.items().get(0).id());
        assertDenied("feedbackNotFound", () -> service.mine("alice", bobRow.id()));

        assertDenied(
                "forbidden",
                () -> service.managed("alice", new PageRequest(1, 20, ""), "", "", ""));
        assertDenied(
                "forbidden",
                () ->
                        service.handle(
                                "alice",
                                bobRow.id(),
                                new FeedbackService.Handle(1L, "RESOLVED", "unauthorized change")));
        assertDenied(
                "forbidden",
                () -> service.managed("scoped-manager", new PageRequest(1, 20, ""), "", "", ""));
        assertDenied("forbidden", () -> service.managed("scoped-manager", bobRow.id()));
        assertDenied(
                "forbidden",
                () ->
                        service.handle(
                                "scoped-manager",
                                bobRow.id(),
                                new FeedbackService.Handle(1L, "RESOLVED", "unauthorized change")));
        assertEquals("OPEN", store.findOwned(bob, bobRow.id()).status());
    }

    @Test
    void directImageReadsAndManagementActionsRequireOwnerOrGlobalManager() {
        var bobRow = create(bob, "bob", "Bob feedback");
        UUID bobImage = UUID.randomUUID();

        assertDenied("fileNotFound", () -> service.image("alice", bobImage));
        verify(files).findReadyImageForContent("alice", bobImage);
        assertDenied("fileNotFound", () -> service.image("scoped-manager", bobImage));
        verify(files).findReadyImageForContent("scoped-manager", bobImage);

        var all = service.managed("administrator", new PageRequest(1, 20, ""), "", "", "");
        assertEquals(1, all.total());
        assertEquals(bobRow.id(), service.managed("administrator", bobRow.id()).id());
        assertTrue(service.image("administrator", bobImage).sizeBytes() > 0);
        var handled =
                service.handle(
                        "administrator",
                        bobRow.id(),
                        new FeedbackService.Handle(1L, "RESOLVED", "fixed"));
        assertEquals("RESOLVED", handled.status());
        assertEquals(2, handled.version());
    }
}
