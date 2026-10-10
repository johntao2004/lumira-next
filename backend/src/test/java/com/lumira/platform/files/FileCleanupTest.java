package com.lumira.platform.files;

import static org.mockito.Mockito.*;

import com.lumira.platform.audit.AuditRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;

class FileCleanupTest {
    private FileRepository.Entry entry() {
        return new FileRepository.Entry(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "local",
                "name",
                "DELETING",
                0L,
                "a".repeat(64),
                2,
                Instant.EPOCH,
                Instant.EPOCH,
                null);
    }

    @Test
    void failureDoesNotBlockLaterFilesOrReleaseFailedMetadata() throws Exception {
        var repo = mock(FileRepository.class);
        var blobs = mock(BlobStore.class);
        var audit = mock(AuditRepository.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var bad = entry();
        var good = entry();
        when(repo.pendingCleanup()).thenReturn(List.of(bad, good));
        when(repo.findDeleting(bad.id(), bad.version())).thenReturn(java.util.Optional.of(bad));
        when(repo.findDeleting(good.id(), good.version())).thenReturn(java.util.Optional.of(good));
        doThrow(new IOException()).when(blobs).delete(bad.id());
        when(repo.finishDelete(good.id(), 2)).thenReturn(true);
        new FileCleanup(repo, blobs, audit, manager).sweep();
        verify(repo).deferCleanup(bad.id(), 2);
        verify(repo, never()).finishDelete(bad.id(), 2);
        verify(audit, never()).append(anyString(), anyString(), eq(bad.id()));
        var order = inOrder(blobs, repo, audit);
        order.verify(blobs).delete(good.id());
        order.verify(repo).finishDelete(good.id(), 2);
        order.verify(audit).append("system:file-cleanup", "file.cleanup", good.id());
    }

    @Test
    void concurrentCompletionDoesNotDuplicateAudit() throws Exception {
        var repo = mock(FileRepository.class);
        var blobs = mock(BlobStore.class);
        var audit = mock(AuditRepository.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var stale = entry();
        when(repo.pendingCleanup()).thenReturn(List.of(stale));
        new FileCleanup(repo, blobs, audit, manager).sweep();
        verify(blobs, never()).delete(stale.id());
        verifyNoInteractions(audit);
    }

    @Test
    void deferredDeletionRetriesUntilBlobStoreDeleteSucceeds() throws Exception {
        var repo = mock(FileRepository.class);
        var blobs = mock(BlobStore.class);
        var audit = mock(AuditRepository.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var pending = entry();
        when(repo.pendingCleanup()).thenReturn(List.of(pending));
        when(repo.findDeleting(pending.id(), pending.version()))
                .thenReturn(java.util.Optional.of(pending));
        doThrow(new IOException("transient storage failure"))
                .doNothing()
                .when(blobs)
                .delete(pending.id());
        when(repo.finishDelete(pending.id(), pending.version())).thenReturn(true);
        var cleanup = new FileCleanup(repo, blobs, audit, manager);

        cleanup.sweep();

        verify(repo).deferCleanup(pending.id(), pending.version());
        verify(repo, never()).finishDelete(pending.id(), pending.version());
        verify(audit, never()).append("system:file-cleanup", "file.cleanup", pending.id());

        cleanup.sweep();

        verify(blobs, times(2)).delete(pending.id());
        verify(repo).finishDelete(pending.id(), pending.version());
        verify(audit).append("system:file-cleanup", "file.cleanup", pending.id());
    }

    @Test
    void deferredPhysicalDeletionKeepsQuotaAndRetriesUntilStorageIsEmpty() throws Exception {
        var repo = mock(FileRepository.class);
        var blobs = mock(BlobStore.class);
        var audit = mock(AuditRepository.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var pending = entry();
        when(repo.pendingCleanup()).thenReturn(List.of(pending));
        when(repo.findDeleting(pending.id(), pending.version())).thenReturn(Optional.of(pending));
        when(blobs.deletionPending(pending.id())).thenReturn(true, false);
        when(repo.finishDelete(pending.id(), pending.version())).thenReturn(true);
        var cleanup = new FileCleanup(repo, blobs, audit, manager);

        cleanup.sweep();

        verify(blobs).delete(pending.id());
        verify(repo).deferCleanup(pending.id(), pending.version());
        verify(repo, never()).finishDelete(pending.id(), pending.version());
        verify(audit, never()).append("system:file-cleanup", "file.cleanup", pending.id());

        cleanup.sweep();

        verify(blobs, times(2)).delete(pending.id());
        verify(repo).finishDelete(pending.id(), pending.version());
        verify(audit).append("system:file-cleanup", "file.cleanup", pending.id());
    }
}
