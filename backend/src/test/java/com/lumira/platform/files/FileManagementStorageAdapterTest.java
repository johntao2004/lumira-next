package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

class FileManagementStorageAdapterTest {
    @Test
    void sharedPortUsesTheRealBoundedTextDecoder() throws Exception {
        var blobs = mock(BlobStore.class);
        var id = UUID.randomUUID();
        var content = "managed preview".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(blobs.open(id)).thenReturn(new java.io.ByteArrayInputStream(content));
        var adapter =
                new FileManagementStorageAdapter(
                        mock(FileRepository.class),
                        blobs,
                        mock(AuditPort.class),
                        mock(PlatformTransactionManager.class));
        assertEquals(
                new FileManagementStoragePort.FilePreviewView("managed preview", false, null, 1, 1),
                adapter.preview(
                        new FileManagementStoragePort.Blob(
                                id, "notes.txt", "READY", (long) content.length),
                        1));
    }

    @Test
    void unsupportedStateAndOversizedMetadataAreRejectedBeforeStorageIO() {
        var blobs = mock(BlobStore.class);
        var adapter =
                new FileManagementStorageAdapter(
                        mock(FileRepository.class),
                        blobs,
                        mock(AuditPort.class),
                        mock(PlatformTransactionManager.class));
        var id = UUID.randomUUID();
        assertThrows(
                ResponseStatusException.class,
                () ->
                        adapter.open(
                                new FileManagementStoragePort.Blob(
                                        id, "notes.txt", "TRASHED", 1L)));
        assertThrows(
                ResponseStatusException.class,
                () ->
                        adapter.preview(
                                new FileManagementStoragePort.Blob(
                                        id, "notes.txt", "READY", FileService.MAXIMUM_BYTES + 1),
                                1));
        verifyNoInteractions(blobs);
    }
}
