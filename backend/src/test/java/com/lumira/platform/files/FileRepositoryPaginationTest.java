package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.Authentication;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class FileRepositoryPaginationTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private FileRepository files;
    private String schema;
    private final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_file_pages_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(source);
        files = new FileRepository(JdbcClient.create(source));
        jdbc.execute(
                "CREATE TABLE platform_user(id uuid PRIMARY KEY,username text,display_name text)");
        jdbc.execute(
                "CREATE TABLE platform_file(id uuid PRIMARY KEY,owner_id uuid,storage_backend text DEFAULT 'LOCAL',original_name text,state text,size_bytes bigint DEFAULT 12,sha256 text,version bigint DEFAULT 0,created_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now(),trashed_at timestamptz)");
        // Managed projections include the three reference sources used by deletion guards.
        jdbc.execute(
                "CREATE TABLE platform_account_media(user_id uuid NOT NULL,avatar_file_id uuid)");
        jdbc.execute(
                "CREATE TABLE platform_feedback_file(feedback_id uuid NOT NULL,file_id uuid NOT NULL)");
        jdbc.execute("CREATE TABLE platform_branding_file(file_id uuid PRIMARY KEY)");
        jdbc.update(
                "INSERT INTO platform_user VALUES(?, 'alice', 'Alice'), (?, 'bob', 'Bob')",
                alice,
                bob);
        add(alice, "old.txt", "READY", "2026-01-01");
        add(alice, "new.txt", "READY", "2026-01-02");
        add(bob, "bob.txt", "READY", "2026-01-03");
        add(alice, "alice-trash.txt", "TRASHED", "2026-01-04");
        add(bob, "bob-trash.txt", "TRASHED", "2026-01-05");
        add(alice, "deleting.txt", "DELETING", "2026-01-06");
    }

    @AfterEach
    void cleanUp() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private UUID add(UUID owner, String name, String state, String time) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_file(id,owner_id,original_name,state,created_at,trashed_at) VALUES(?,?,?,?,CAST(? AS timestamptz),CASE WHEN ?='TRASHED' THEN CAST(? AS timestamptz) ELSE NULL END)",
                id,
                owner,
                name,
                state,
                time,
                state,
                time);
        return id;
    }

    @Test
    void ownerScopeOrderingAndCountsSurviveSharedPaging() {
        var first = files.listOwned(alice, new PageRequest(1, 1, ""));
        assertEquals(2, first.total());
        assertEquals("new.txt", first.items().getFirst().originalName());
        var second = files.listOwned(alice, new PageRequest(2, 1, ""));
        assertEquals(2, second.total());
        assertEquals(2, second.page());
        assertEquals("old.txt", second.items().getFirst().originalName());
        assertEquals(0, files.listOwned(alice, new PageRequest(1, 10, "bob")).total());
        var filtered =
                files.listOwned(
                        alice,
                        new PageRequest(
                                1,
                                10,
                                "",
                                Map.of("originalName", "new.txt"),
                                Map.of("originalName", "exact")));
        assertEquals(1, filtered.total());
        assertEquals("new.txt", filtered.items().getFirst().originalName());
    }

    @Test
    void managedSearchProjectionAndTrashScopeRemainSeparate() {
        var managed = files.listManaged(new PageRequest(1, 10, "bob"));
        assertEquals(1, managed.total());
        assertEquals("Bob", managed.items().getFirst().ownerDisplayName());
        assertEquals(3, files.listManaged(new PageRequest(1, 10, "")).total());
        var trash = files.listTrashOwned(alice, new PageRequest(1, 10, ""));
        assertEquals(1, trash.total());
        assertEquals("alice-trash.txt", trash.items().getFirst().originalName());
        var allTrash = files.listManagedTrash(new PageRequest(1, 1, ""));
        assertEquals(2, allTrash.total());
        assertEquals("bob-trash.txt", allTrash.items().getFirst().originalName());
        assertEquals(1, files.listManagedTrash(new PageRequest(1, 10, "Alice")).total());
    }

    @Test
    void managedDownloadAndReferenceProtectionUseTheSameDatabaseProjection() throws Exception {
        UUID downloadable = add(alice, "managed-download.txt", "READY", "2026-02-01");
        UUID avatar = add(alice, "avatar-reference.txt", "READY", "2026-02-02");
        UUID feedback = add(alice, "feedback-reference.txt", "READY", "2026-02-03");
        UUID branding = add(alice, "branding-reference.txt", "READY", "2026-02-04");
        jdbc.update(
                "INSERT INTO platform_account_media(user_id,avatar_file_id) VALUES(?,?)",
                alice,
                avatar);
        jdbc.update(
                "INSERT INTO platform_feedback_file(feedback_id,file_id) VALUES(?,?)",
                UUID.randomUUID(),
                feedback);
        jdbc.update("INSERT INTO platform_branding_file(file_id) VALUES(?)", branding);

        assertFalse(files.findManaged(downloadable).orElseThrow().protectedByReference());
        assertTrue(files.findManaged(avatar).orElseThrow().protectedByReference());
        assertTrue(files.findManaged(feedback).orElseThrow().protectedByReference());
        assertTrue(files.findManaged(branding).orElseThrow().protectedByReference());

        byte[] payload = "download-payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jdbc.update(
                "UPDATE platform_file SET size_bytes=? WHERE id=?", payload.length, downloadable);
        var blobs = mock(BlobStore.class);
        when(blobs.open(downloadable)).thenReturn(new java.io.ByteArrayInputStream(payload));
        var service =
                new FileService(
                        files,
                        mock(com.lumira.platform.identity.api.AccountOwnerPort.class),
                        blobs,
                        new DataSourceTransactionManager(jdbc.getDataSource()),
                        mock(AuditPort.class),
                        new FileQuota(Long.MAX_VALUE, 1000),
                        mock(AdministrationPort.class));
        var controller = new AdminFileController(service);
        var actor = mock(Authentication.class);
        when(actor.getName()).thenReturn("qa-admin");

        var response = controller.download(downloadable, actor);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(payload.length, response.getHeaders().getContentLength());
        try (var input = response.getBody().getInputStream()) {
            assertArrayEquals(payload, input.readAllBytes());
        }
    }
}
