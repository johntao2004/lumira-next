package com.lumira.plugins.fileconsole;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.*;
import org.springframework.web.server.ResponseStatusException;

class ManagedFileServiceTest {
    @Test
    void adminPurgeRequestsKernelFinalizationOnlyAfterTheFenceCommit() throws Exception {
        var repo = mock(ManagedFileRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        var blobs = mock(FileManagementStoragePort.class);
        var audit = mock(com.lumira.platform.common.AuditPort.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation -> mock(TransactionStatus.class));
        var id = UUID.randomUUID();
        var owner = UUID.randomUUID();
        when(repo.findManaged(id))
                .thenReturn(
                        Optional.of(
                                new ManagedFileRepository.Entry(
                                        id,
                                        owner,
                                        "user",
                                        "User",
                                        "postgres",
                                        "report.pdf",
                                        "TRASHED",
                                        12L,
                                        "a".repeat(64),
                                        4,
                                        java.time.Instant.EPOCH,
                                        java.time.Instant.EPOCH,
                                        java.time.Instant.EPOCH)));
        when(repo.purgeManaged(id, 4)).thenReturn(true);
        new ManagedFileService(
                        repo,
                        blobs,
                        manager,
                        audit,
                        guard,
                        mock(com.lumira.platform.common.AuthorizationContextBoundaryPort.class))
                .purgeManaged("admin", id, 4);
        verify(guard).requireConcurrentOperationGlobal("admin", PlatformOperation.FILE_PURGE);
        var ordering = inOrder(manager, blobs);
        ordering.verify(manager).commit(any());
        ordering.verify(blobs).finishDeletion(id, 5);
    }

    @Test
    void managedTrashRequiresFileDeleteOperation() {
        var repo = mock(ManagedFileRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var id = UUID.randomUUID();
        var owner = UUID.randomUUID();
        when(repo.findManaged(id))
                .thenReturn(
                        Optional.of(
                                new ManagedFileRepository.Entry(
                                        id,
                                        owner,
                                        "user",
                                        "User",
                                        "postgres",
                                        "report.pdf",
                                        "READY",
                                        12L,
                                        "a".repeat(64),
                                        4,
                                        java.time.Instant.EPOCH,
                                        java.time.Instant.EPOCH,
                                        null)));
        when(repo.requestManagedTrash(id, 4)).thenReturn(true);
        var service =
                new ManagedFileService(
                        repo,
                        mock(FileManagementStoragePort.class),
                        manager,
                        mock(com.lumira.platform.common.AuditPort.class),
                        guard,
                        mock(com.lumira.platform.common.AuthorizationContextBoundaryPort.class));
        service.deleteManaged("admin", id, 4);
        verify(guard).requireConcurrentOperationGlobal("admin", PlatformOperation.FILE_DELETE);
        verify(repo).requestManagedTrash(id, 4);
    }

    @Test
    void managedReadsRequireTheFilesAllPage() {
        var repo = mock(ManagedFileRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        var id = UUID.randomUUID();
        var service =
                new ManagedFileService(
                        repo,
                        mock(FileManagementStoragePort.class),
                        mock(PlatformTransactionManager.class),
                        mock(com.lumira.platform.common.AuditPort.class),
                        guard,
                        mock(com.lumira.platform.common.AuthorizationContextBoundaryPort.class));

        service.listManaged("admin", new PageRequest(1, 10, ""));

        verify(guard).requirePageGlobal("admin", PlatformPageId.FILES_ALL);
        verify(guard, never()).requireOperationGlobal(anyString(), any());
    }

    @Test
    void managedDownloadRequiresDownloadOperationForMetadataAndContent() throws Exception {
        var repo = mock(ManagedFileRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireOperationGlobal("admin", PlatformOperation.FILE_DOWNLOAD);
        var service =
                new ManagedFileService(
                        repo,
                        mock(FileManagementStoragePort.class),
                        mock(PlatformTransactionManager.class),
                        mock(com.lumira.platform.common.AuditPort.class),
                        guard,
                        mock(com.lumira.platform.common.AuthorizationContextBoundaryPort.class));

        assertThrows(
                ResponseStatusException.class,
                () -> service.findManaged("admin", UUID.randomUUID()));
        assertThrows(
                ResponseStatusException.class,
                () -> service.openManaged("admin", UUID.randomUUID()));

        verify(guard, times(2)).requireOperationGlobal("admin", PlatformOperation.FILE_DOWNLOAD);
        verify(repo, never()).findManaged(any());
    }

    @Test
    void managedPreviewUsesPreviewOperationWithoutDownloadGrant() throws Exception {
        var repo = mock(ManagedFileRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        var blobs = mock(FileManagementStoragePort.class);
        var id = UUID.randomUUID();
        var owner = UUID.randomUUID();
        when(repo.findManaged(id))
                .thenReturn(
                        Optional.of(
                                new ManagedFileRepository.Entry(
                                        id,
                                        owner,
                                        "user",
                                        "User",
                                        "postgres",
                                        "preview.txt",
                                        "READY",
                                        7L,
                                        "a".repeat(64),
                                        1,
                                        java.time.Instant.EPOCH,
                                        java.time.Instant.EPOCH,
                                        null)));
        when(blobs.preview(any(), eq(1)))
                .thenReturn(
                        new FileManagementStoragePort.FilePreviewView(
                                "preview", false, null, 1, 1));
        var service =
                new ManagedFileService(
                        repo,
                        blobs,
                        mock(PlatformTransactionManager.class),
                        mock(com.lumira.platform.common.AuditPort.class),
                        guard,
                        mock(com.lumira.platform.common.AuthorizationContextBoundaryPort.class));

        var preview = service.previewManaged("admin", id, 1);

        assertEquals("preview", preview.text());
        verify(guard).requireOperationGlobal("admin", PlatformOperation.FILE_PREVIEW);
        verify(guard, never()).requireOperationGlobal("admin", PlatformOperation.FILE_DOWNLOAD);
    }

    @Test
    void downloadGrantAloneDoesNotAuthorizeManagedPreview() throws Exception {
        var repo = mock(ManagedFileRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireOperationGlobal("admin", PlatformOperation.FILE_PREVIEW);
        var service =
                new ManagedFileService(
                        repo,
                        mock(FileManagementStoragePort.class),
                        mock(PlatformTransactionManager.class),
                        mock(com.lumira.platform.common.AuditPort.class),
                        guard,
                        mock(com.lumira.platform.common.AuthorizationContextBoundaryPort.class));

        assertThrows(
                ResponseStatusException.class,
                () -> service.previewManaged("admin", UUID.randomUUID(), 1));

        verify(guard).requireOperationGlobal("admin", PlatformOperation.FILE_PREVIEW);
        verify(guard, never()).requireOperationGlobal("admin", PlatformOperation.FILE_DOWNLOAD);
        verify(repo, never()).findManaged(any());
    }

    @Test
    void managedRestoreRequiresRestoreOperationWithoutDeleteGrant() {
        var repo = mock(ManagedFileRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var id = UUID.randomUUID();
        var owner = UUID.randomUUID();
        when(repo.findManaged(id))
                .thenReturn(
                        Optional.of(
                                new ManagedFileRepository.Entry(
                                        id,
                                        owner,
                                        "user",
                                        "User",
                                        "postgres",
                                        "report.pdf",
                                        "TRASHED",
                                        12L,
                                        "a".repeat(64),
                                        4,
                                        java.time.Instant.EPOCH,
                                        java.time.Instant.EPOCH,
                                        java.time.Instant.EPOCH)));
        when(repo.restoreManaged(id, 4)).thenReturn(true);
        var service =
                new ManagedFileService(
                        repo,
                        mock(FileManagementStoragePort.class),
                        manager,
                        mock(com.lumira.platform.common.AuditPort.class),
                        guard,
                        mock(com.lumira.platform.common.AuthorizationContextBoundaryPort.class));

        service.restoreManaged("admin", id, 4);

        verify(guard).requireConcurrentOperationGlobal("admin", PlatformOperation.FILE_RESTORE);
        verify(guard, never())
                .requireConcurrentOperationGlobal("admin", PlatformOperation.FILE_DELETE);
        verify(repo).restoreManaged(id, 4);
    }

    @Test
    void forbiddenManagedPurgeNeverReachesMetadataOrStorage() {
        var repo = mock(ManagedFileRepository.class);
        var storage = mock(FileManagementStoragePort.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireConcurrentOperationGlobal("admin", PlatformOperation.FILE_PURGE);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var service =
                new ManagedFileService(
                        repo,
                        storage,
                        manager,
                        mock(com.lumira.platform.common.AuditPort.class),
                        guard,
                        mock(com.lumira.platform.common.AuthorizationContextBoundaryPort.class));
        assertThrows(
                ResponseStatusException.class,
                () -> service.purgeManaged("admin", UUID.randomUUID(), 9));
        verifyNoInteractions(repo, storage);
    }
}
