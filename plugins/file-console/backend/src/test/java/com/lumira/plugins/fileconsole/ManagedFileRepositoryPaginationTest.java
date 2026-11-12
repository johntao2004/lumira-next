package com.lumira.plugins.fileconsole;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.Authentication;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class ManagedFileRepositoryPaginationTest extends com.lumira.testfixtures.FileQueryFixture {
    private ManagedFileRepository files;

    @BeforeEach
    void repository() {
        files = new ManagedFileRepository(client);
    }

    @Test
    void managedSearchProjectionAndTrashScopeRemainSeparate() {
        var managed = files.listManaged(new PageRequest(1, 10, "bob"));
        assertEquals(1, managed.total());
        assertEquals("Bob", managed.items().getFirst().ownerDisplayName());
        assertEquals(3, files.listManaged(new PageRequest(1, 10, "")).total());
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
        var blobs = mock(com.lumira.platform.common.FileManagementStoragePort.class);
        when(blobs.open(any(com.lumira.platform.common.FileManagementStoragePort.Blob.class)))
                .thenReturn(new java.io.ByteArrayInputStream(payload));
        var service =
                new ManagedFileService(
                        files,
                        blobs,
                        new DataSourceTransactionManager(jdbc.getDataSource()),
                        mock(AuditPort.class),
                        mock(AdministrationPort.class),
                        mock(com.lumira.platform.common.AuthorizationContextBoundaryPort.class));
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
