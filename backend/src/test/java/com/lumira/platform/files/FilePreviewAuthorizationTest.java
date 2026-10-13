package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
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
    void downloadGrantAloneDoesNotAuthorizeManagedPreview() throws Exception {
        var repo = mock(FileRepository.class);
        var blobs = mock(BlobStore.class);
        var guard = globalGuard(PlatformOperation.FILE_DOWNLOAD);
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
        var guard = globalGuard(PlatformOperation.FILE_PREVIEW);
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
        assertThrows(
                ResponseStatusException.class,
                () -> guard.requireOperationGlobal("admin", PlatformOperation.FILE_DOWNLOAD));
    }

    private AdministrationPort globalGuard(PlatformOperation grantedOperation) {
        var grants =
                java.util.Set.of(
                        PlatformPageId.FILES_ALL.accessPermission(), grantedOperation.permission());
        return new AdministrationPort() {
            @Override
            public java.util.Set<String> require(String actor, String permission) {
                if (!grants.contains(permission))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
                return grants;
            }

            @Override
            public void requireUser(String actor, UUID target, String permission) {
                if (!grants.contains(permission))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
            }

            @Override
            public void requireGlobal(String actor, String permission) {
                if (!grants.contains(permission))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
            }

            @Override
            public boolean hasGlobal(String actor, String permission) {
                return grants.contains(permission);
            }
        };
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
