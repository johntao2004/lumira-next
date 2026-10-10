package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.identity.api.AccountOwnerPort;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

class FilePreviewAuthorizationTest {
    @Test
    void managedPreviewRequiresGlobalReadBeforeOpeningBlob() throws Exception {
        var repo = mock(FileRepository.class);
        var blobs = mock(BlobStore.class);
        var guard = mock(AdministrationPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireGlobal("actor", "platform:files:read");
        var service = service(repo, blobs, guard);

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.previewManaged("actor", UUID.randomUUID(), 1));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(repo, never()).findManaged(any());
        verify(blobs, never()).open(any());
    }

    @Test
    void managedPreviewReadsTextThroughSameDecoder() throws Exception {
        var repo = mock(FileRepository.class);
        var blobs = mock(BlobStore.class);
        var guard = mock(AdministrationPort.class);
        var id = UUID.randomUUID();
        byte[] content = "managed preview".getBytes(StandardCharsets.UTF_8);
        when(repo.findManaged(id))
                .thenReturn(
                        Optional.of(
                                new FileRepository.ManagedEntry(
                                        id,
                                        UUID.randomUUID(),
                                        "user",
                                        "User",
                                        "postgres",
                                        "notes.txt",
                                        "READY",
                                        (long) content.length,
                                        "a".repeat(64),
                                        4,
                                        Instant.EPOCH,
                                        Instant.EPOCH,
                                        null)));
        when(blobs.open(id)).thenReturn(new ByteArrayInputStream(content));

        assertEquals(
                "managed preview",
                service(repo, blobs, guard).previewManaged("admin", id, 1).text());
        verify(guard).requireGlobal("admin", "platform:files:read");
    }

    private FileService service(FileRepository repo, BlobStore blobs, AdministrationPort guard) {
        return new FileService(
                repo,
                mock(AccountOwnerPort.class),
                blobs,
                mock(PlatformTransactionManager.class),
                mock(AuditPort.class),
                new FileQuota(Long.MAX_VALUE, 1000),
                guard);
    }
}
