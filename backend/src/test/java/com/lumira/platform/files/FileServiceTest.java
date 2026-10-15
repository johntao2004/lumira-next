package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.identity.ProfileRepository;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.util.*;

class FileServiceTest {
    private FileRepository.Entry entry(UUID id, UUID owner, String state, long version) {
        return new FileRepository.Entry(
                id,
                owner,
                "postgres",
                "report.pdf",
                state,
                12L,
                "a".repeat(64),
                version,
                java.time.Instant.EPOCH,
                java.time.Instant.EPOCH,
                state.equals("TRASHED") ? java.time.Instant.EPOCH : null);
    }

    @Test
    void lostUploadStatePreventsPhysicalPublication() throws Exception {
        var repo = mock(FileRepository.class);
        when(repo.usage(any())).thenReturn(new FileRepository.Usage(0, 0));
        var profile = mock(ProfileRepository.class);
        var store = mock(BlobStore.class);
        var stage = mock(BlobStore.Staged.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(profile.find("actor", true))
                .thenReturn(
                        new ProfileRepository.Profile(
                                UUID.randomUUID(), "actor", "Actor", null, null, null, 0));
        when(store.stage(any(), any(), anyLong())).thenReturn(stage);
        when(stage.metadata())
                .thenReturn(new BlobStore.Stored(UUID.randomUUID(), 1, "a".repeat(64)));
        var service =
                new FileService(
                        repo,
                        profile,
                        store,
                        manager,
                        mock(com.lumira.platform.audit.AuditRepository.class),
                        new FileQuota(Long.MAX_VALUE, 1000));
        assertThrows(
                ResponseStatusException.class,
                () -> service.upload("actor", "file", InputStream.nullInputStream()));
        verify(stage, never()).publish();
        verify(stage).close();
        verify(repo, never()).complete(any(), any(), anyLong(), anyLong(), anyString());
    }

    @Test
    void rejectsUnsafeNames() {
        for (String name : List.of("", " ", "..", "../a", "a/b", "a\\b", "a\r\nb", "a".repeat(256)))
            assertThrows(ResponseStatusException.class, () -> FileService.filename(name));
        assertEquals("report.pdf", FileService.filename(" report.pdf "));
    }

    @Test
    void unlimitedStorageDoesNotRemovePerFileUploadLimit() throws IOException {
        var repo = mock(FileRepository.class);
        var accounts = mock(com.lumira.platform.identity.api.AccountOwnerPort.class);
        var store = mock(BlobStore.class);
        var manager = mock(PlatformTransactionManager.class);
        var service =
                new FileService(
                        repo,
                        accounts,
                        store,
                        manager,
                        mock(com.lumira.platform.common.AuditPort.class),
                        new FileQuota(0, 100));

        assertThrows(
                BlobStore.TooLarge.class,
                () ->
                        service.upload(
                                "actor",
                                "large",
                                InputStream.nullInputStream(),
                                FileService.MAXIMUM_BYTES + 1));

        verify(store, never()).stage(any(UUID.class), any(), anyLong());
        verify(repo, never()).reserve(any(), any(), anyString(), anyString(), anyLong());
    }

    @Test
    void zeroFileLimitAllowsUploadEvenWhenTheOwnerAlreadyHasFiles() throws Exception {
        var repo = mock(FileRepository.class);
        var accounts = mock(com.lumira.platform.identity.api.AccountOwnerPort.class);
        var store = mock(BlobStore.class);
        var stage = mock(BlobStore.Staged.class);
        var manager = mock(PlatformTransactionManager.class);
        var owner = UUID.randomUUID();
        var digest = "a".repeat(64);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(accounts.id("actor", true)).thenReturn(owner);
        when(repo.quotaOwner(owner, true))
                .thenReturn(new FileRepository.QuotaOwner(owner, "actor", "Actor", 0));
        when(repo.usage(owner)).thenReturn(new FileRepository.Usage(512, 100));
        when(store.stage(any(UUID.class), any(), eq(1L))).thenReturn(stage);
        when(stage.metadata()).thenReturn(new BlobStore.Stored(UUID.randomUUID(), 1, digest));
        when(repo.lockUpload(any(UUID.class), eq(owner))).thenReturn(true);
        when(repo.complete(any(UUID.class), eq(owner), eq(0L), eq(1L), eq(digest)))
                .thenReturn(true);
        when(repo.findOwned(any(UUID.class), eq(owner)))
                .thenAnswer(
                        invocation ->
                                Optional.of(entry(invocation.getArgument(0), owner, "READY", 1)));

        var service =
                new FileService(
                        repo,
                        accounts,
                        store,
                        manager,
                        mock(com.lumira.platform.common.AuditPort.class),
                        new FileQuota(0, 0));

        var uploaded =
                service.upload(
                        "actor", "unlimited.txt", new ByteArrayInputStream(new byte[] {1}), 1);

        assertEquals(owner, uploaded.ownerId());
        assertEquals("READY", uploaded.state());
        verify(repo)
                .reserve(any(UUID.class), eq(owner), eq("postgres"), eq("unlimited.txt"), eq(1L));
        verify(stage).publish();
    }

    @Test
    void positiveFileLimitStillRejectsUploadAtTheBoundary() throws Exception {
        var repo = mock(FileRepository.class);
        var accounts = mock(com.lumira.platform.identity.api.AccountOwnerPort.class);
        var manager = mock(PlatformTransactionManager.class);
        var owner = UUID.randomUUID();
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(accounts.id("actor", true)).thenReturn(owner);
        when(repo.quotaOwner(owner, true))
                .thenReturn(new FileRepository.QuotaOwner(owner, "actor", "Actor", 0));
        when(repo.usage(owner)).thenReturn(new FileRepository.Usage(0, 2));

        var service =
                new FileService(
                        repo,
                        accounts,
                        mock(BlobStore.class),
                        manager,
                        mock(com.lumira.platform.common.AuditPort.class),
                        new FileQuota(0, 2));

        assertThrows(
                ResponseStatusException.class,
                () ->
                        service.upload(
                                "actor",
                                "at-limit.txt",
                                new ByteArrayInputStream(new byte[] {1}),
                                1));
        verify(repo, never()).reserve(any(), any(), anyString(), anyString(), anyLong());
    }

    @Test
    void unlimitedStorageUsageAndPreviewKeepZeroAsTheReportedLimit() {
        var repo = mock(FileRepository.class);
        var accounts = mock(com.lumira.platform.identity.api.AccountOwnerPort.class);
        var policies = mock(com.lumira.platform.common.FileQuotaPolicyPort.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        var owner = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        long chargedBytes = Long.MAX_VALUE - 10;
        when(accounts.id("actor", false)).thenReturn(owner);
        when(repo.usage(owner)).thenReturn(new FileRepository.Usage(chargedBytes, 1));
        when(repo.quotaOwner(owner, false))
                .thenReturn(new FileRepository.QuotaOwner(owner, "actor", "Actor", 3));
        when(policies.forUser(owner))
                .thenReturn(new com.lumira.platform.common.FileQuotaPolicyPort.Limits(0, 5));
        when(policies.forRoles(Set.of(roleId)))
                .thenReturn(new com.lumira.platform.common.FileQuotaPolicyPort.Limits(0, 4));
        var manager = mock(PlatformTransactionManager.class);
        var defaults =
                new java.util.concurrent.atomic.AtomicReference<>(
                        new com.lumira.platform.common.FileQuotaPolicyPort.Limits(1024, 100));
        var service =
                new FileService(
                        repo,
                        accounts,
                        mock(BlobStore.class),
                        manager,
                        mock(com.lumira.platform.common.AuditPort.class),
                        new FileQuota(1024, 100),
                        guard,
                        policies,
                        null,
                        defaults::get);

        var usage = service.usage("actor");
        var userQuota = service.userQuota("actor", owner);
        defaults.set(new com.lumira.platform.common.FileQuotaPolicyPort.Limits(2048, 200));
        var preview = service.previewUserQuota("actor", owner, Set.of(roleId));

        assertEquals(chargedBytes, usage.chargedBytes());
        assertEquals(0, usage.maximumBytes());
        assertEquals(5, usage.maximumFiles());
        assertEquals(0, userQuota.maximumBytes());
        assertEquals(1024, userQuota.defaultMaximumBytes());
        assertEquals(100, userQuota.defaultMaximumFiles());
        assertEquals(0, preview.currentMaximumBytes());
        assertEquals(0, preview.proposedMaximumBytes());
        assertEquals(chargedBytes, preview.chargedBytes());
        assertEquals(2048, preview.defaultMaximumBytes());
        assertEquals(200, preview.defaultMaximumFiles());
        verify(guard).requirePage("actor", PlatformPageId.USERS);
        verify(guard).requireUser("actor", owner, PlatformPageId.USERS.accessPermission());
        verify(guard).requireOperationUser("actor", owner, PlatformOperation.USER_ASSIGN_ROLES);
    }

    @Test
    void failedStreamingDeletesBytesBeforeMetadata() throws Exception {
        var repo = mock(FileRepository.class);
        when(repo.usage(any())).thenReturn(new FileRepository.Usage(0, 0));
        var profile = mock(ProfileRepository.class);
        var store = mock(BlobStore.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(profile.find("actor", true))
                .thenReturn(
                        new ProfileRepository.Profile(
                                UUID.randomUUID(), "actor", "Actor", null, null, null, 0));
        when(store.stage(any(UUID.class), any(), anyLong()))
                .thenThrow(new IOException("failed stream"));
        when(repo.abandonUpload(any())).thenReturn(true);
        var service =
                new FileService(
                        repo,
                        profile,
                        store,
                        manager,
                        mock(com.lumira.platform.audit.AuditRepository.class),
                        new FileQuota(Long.MAX_VALUE, 1000));
        assertThrows(
                IOException.class,
                () -> service.upload("actor", "file", InputStream.nullInputStream()));
        var order = inOrder(repo, store);
        order.verify(repo)
                .reserve(any(), any(), eq("postgres"), eq("file"), eq(FileService.MAXIMUM_BYTES));
        order.verify(store).stage(any(UUID.class), any(), anyLong());
        order.verify(repo).abandonUpload(any());
        order.verify(store).delete(any());
        order.verify(repo).finishDelete(any(), eq(1L));
    }

    @Test
    void failedPhysicalCleanupRetainsMetadata() throws Exception {
        var repo = mock(FileRepository.class);
        when(repo.usage(any())).thenReturn(new FileRepository.Usage(0, 0));
        var profile = mock(ProfileRepository.class);
        var store = mock(BlobStore.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(profile.find("actor", true))
                .thenReturn(
                        new ProfileRepository.Profile(
                                UUID.randomUUID(), "actor", "Actor", null, null, null, 0));
        when(store.stage(any(UUID.class), any(), anyLong())).thenThrow(new IOException("stream"));
        when(repo.abandonUpload(any())).thenReturn(true);
        doThrow(new IOException("cleanup")).when(store).delete(any());
        var failure =
                assertThrows(
                        IOException.class,
                        () ->
                                new FileService(
                                                repo,
                                                profile,
                                                store,
                                                manager,
                                                mock(
                                                        com.lumira.platform.audit.AuditRepository
                                                                .class),
                                                new FileQuota(Long.MAX_VALUE, 1000))
                                        .upload("actor", "file", InputStream.nullInputStream()));
        assertEquals(1, failure.getSuppressed().length);
        verify(repo, never()).finishDelete(any(), anyLong());
    }

    @Test
    void doesNotDeleteWhenUploadCannotBeAbandoned() throws Exception {
        var repo = mock(FileRepository.class);
        when(repo.usage(any())).thenReturn(new FileRepository.Usage(0, 0));
        var profile = mock(ProfileRepository.class);
        var store = mock(BlobStore.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(profile.find("actor", true))
                .thenReturn(
                        new ProfileRepository.Profile(
                                UUID.randomUUID(), "actor", "Actor", null, null, null, 0));
        when(store.stage(any(UUID.class), any(), anyLong()))
                .thenThrow(new IOException("uncertain result"));
        when(repo.abandonUpload(any())).thenReturn(false);
        assertThrows(
                IOException.class,
                () ->
                        new FileService(
                                        repo,
                                        profile,
                                        store,
                                        manager,
                                        mock(com.lumira.platform.audit.AuditRepository.class),
                                        new FileQuota(Long.MAX_VALUE, 1000))
                                .upload("actor", "file", InputStream.nullInputStream()));
        verify(store, never()).delete(any());
        verify(repo, never()).finishDelete(any(), anyLong());
    }

    @Test
    void ownerDeleteMovesFileToTrashWithoutDeletingBlob() throws Exception {
        var repo = mock(FileRepository.class);
        var accounts = mock(com.lumira.platform.identity.api.AccountOwnerPort.class);
        var blobs = mock(BlobStore.class);
        var audit = mock(com.lumira.platform.common.AuditPort.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var owner = UUID.randomUUID();
        var id = UUID.randomUUID();
        when(accounts.id("actor", false)).thenReturn(owner);
        when(repo.findOwned(id, owner)).thenReturn(Optional.of(entry(id, owner, "READY", 4)));
        when(repo.requestTrash(id, owner, 4)).thenReturn(true);
        var service =
                new FileService(
                        repo, accounts, blobs, manager, audit, new FileQuota(Long.MAX_VALUE, 1000));
        service.delete("actor", id, 4);
        verify(repo).requestTrash(id, owner, 4);
        verify(blobs, never()).delete(any());
        verify(audit).append("actor", "file.trash", id);
    }

    @Test
    void restoreAndPurgeRemainOwnerScopedAndVersioned() throws Exception {
        var repo = mock(FileRepository.class);
        var accounts = mock(com.lumira.platform.identity.api.AccountOwnerPort.class);
        var blobs = mock(BlobStore.class);
        var audit = mock(com.lumira.platform.common.AuditPort.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var owner = UUID.randomUUID();
        var id = UUID.randomUUID();
        when(accounts.id("actor", true)).thenReturn(owner);
        when(repo.findOwned(id, owner)).thenReturn(Optional.of(entry(id, owner, "TRASHED", 5)));
        when(repo.restoreOwned(id, owner, 5)).thenReturn(true);
        when(repo.purgeOwned(id, owner, 5)).thenReturn(true);
        when(repo.findDeleting(id, 6)).thenReturn(Optional.of(entry(id, owner, "DELETING", 6)));
        when(repo.finishDelete(id, 6)).thenReturn(true);
        var service =
                new FileService(
                        repo, accounts, blobs, manager, audit, new FileQuota(Long.MAX_VALUE, 1000));
        service.restore("actor", id, 5);
        service.purge("actor", id, 5);
        verify(repo).restoreOwned(id, owner, 5);
        verify(repo).purgeOwned(id, owner, 5);
        verify(repo, times(2)).findOwned(id, owner);
        verify(blobs).delete(id);
        verify(repo).finishDelete(id, 6);
    }

    @Test
    void manualPurgeRunsStorageAfterFenceCommitAndReleasesByVersionCas() throws Exception {
        var repo = mock(FileRepository.class);
        var accounts = mock(com.lumira.platform.identity.api.AccountOwnerPort.class);
        var blobs = mock(BlobStore.class);
        var audit = mock(com.lumira.platform.common.AuditPort.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation -> mock(TransactionStatus.class));
        var owner = UUID.randomUUID();
        var id = UUID.randomUUID();
        when(accounts.id("actor", true)).thenReturn(owner);
        when(repo.findOwned(id, owner)).thenReturn(Optional.of(entry(id, owner, "TRASHED", 11)));
        when(repo.purgeOwned(id, owner, 11)).thenReturn(true);
        when(repo.findDeleting(id, 12)).thenReturn(Optional.of(entry(id, owner, "DELETING", 12)));
        when(repo.finishDelete(id, 12)).thenReturn(true);
        new FileService(repo, accounts, blobs, manager, audit, new FileQuota(Long.MAX_VALUE, 1000))
                .purge("actor", id, 11);
        var order = inOrder(repo, manager, blobs);
        order.verify(repo).purgeOwned(id, owner, 11);
        order.verify(manager).commit(any());
        order.verify(repo).findDeleting(id, 12);
        order.verify(blobs).delete(id);
        order.verify(repo).finishDelete(id, 12);
        order.verify(manager).commit(any());
        verify(audit).append("actor", "file.purgeRequested", id);
        verify(audit).append("system:file-cleanup", "file.cleanup", id);
    }

    @Test
    void failedImmediatePurgeKeepsMetadataPendingAndQuotaCharged() throws Exception {
        var repo = mock(FileRepository.class);
        var accounts = mock(com.lumira.platform.identity.api.AccountOwnerPort.class);
        var blobs = mock(BlobStore.class);
        var audit = mock(com.lumira.platform.common.AuditPort.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation -> mock(TransactionStatus.class));
        var owner = UUID.randomUUID();
        var id = UUID.randomUUID();
        when(accounts.id("actor", true)).thenReturn(owner);
        when(repo.findOwned(id, owner)).thenReturn(Optional.of(entry(id, owner, "TRASHED", 2)));
        when(repo.purgeOwned(id, owner, 2)).thenReturn(true);
        when(repo.findDeleting(id, 3)).thenReturn(Optional.of(entry(id, owner, "DELETING", 3)));
        doThrow(new IOException("storage unavailable")).when(blobs).delete(id);
        assertDoesNotThrow(
                () ->
                        new FileService(
                                        repo,
                                        accounts,
                                        blobs,
                                        manager,
                                        audit,
                                        new FileQuota(Long.MAX_VALUE, 1000))
                                .purge("actor", id, 2));
        verify(repo).deferCleanup(id, 3);
        verify(repo, never()).finishDelete(id, 3);
        verify(audit, never()).append("system:file-cleanup", "file.cleanup", id);
    }

    @Test
    void staleAndForbiddenPurgeNeverReachStorage() throws Exception {
        var repo = mock(FileRepository.class);
        var accounts = mock(com.lumira.platform.identity.api.AccountOwnerPort.class);
        var blobs = mock(BlobStore.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation -> mock(TransactionStatus.class));
        var owner = UUID.randomUUID();
        var id = UUID.randomUUID();
        when(accounts.id("actor", true)).thenReturn(owner);
        when(repo.findOwned(id, owner)).thenReturn(Optional.of(entry(id, owner, "TRASHED", 9)));
        var service =
                new FileService(
                        repo,
                        accounts,
                        blobs,
                        manager,
                        mock(com.lumira.platform.common.AuditPort.class),
                        new FileQuota(Long.MAX_VALUE, 1000));
        assertThrows(ResponseStatusException.class, () -> service.purge("actor", id, 8));
        verify(blobs, never()).delete(any());
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireOperationGlobal("admin", PlatformOperation.FILE_PURGE);
        var managed =
                new FileService(
                        repo,
                        accounts,
                        blobs,
                        manager,
                        mock(com.lumira.platform.common.AuditPort.class),
                        new FileQuota(Long.MAX_VALUE, 1000),
                        guard);
        assertThrows(ResponseStatusException.class, () -> managed.purgeManaged("admin", id, 9));
        verify(blobs, never()).delete(any());
    }

    @Test
    void adminPurgeUsesGlobalGuardAndImmediateFinalizer() throws Exception {
        var repo = mock(FileRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        var blobs = mock(BlobStore.class);
        var audit = mock(com.lumira.platform.common.AuditPort.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation -> mock(TransactionStatus.class));
        var id = UUID.randomUUID();
        var owner = UUID.randomUUID();
        when(repo.findManaged(id))
                .thenReturn(
                        Optional.of(
                                new FileRepository.ManagedEntry(
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
        when(repo.findDeleting(id, 5)).thenReturn(Optional.of(entry(id, owner, "DELETING", 5)));
        when(repo.finishDelete(id, 5)).thenReturn(true);
        new FileService(
                        repo,
                        mock(com.lumira.platform.identity.api.AccountOwnerPort.class),
                        blobs,
                        manager,
                        audit,
                        new FileQuota(Long.MAX_VALUE, 1000),
                        guard)
                .purgeManaged("admin", id, 4);
        verify(guard).requireOperationGlobal("admin", PlatformOperation.FILE_PURGE);
        verify(blobs).delete(id);
        verify(repo).finishDelete(id, 5);
    }

    @Test
    void previewCannotReadAnotherOwnersFile() throws Exception {
        var repo = mock(FileRepository.class);
        var accounts = mock(com.lumira.platform.identity.api.AccountOwnerPort.class);
        var blobs = mock(BlobStore.class);
        var manager = mock(PlatformTransactionManager.class);
        var owner = UUID.randomUUID();
        var id = UUID.randomUUID();
        when(accounts.id("actor", false)).thenReturn(owner);
        when(repo.findOwned(id, owner)).thenReturn(Optional.empty());
        var service =
                new FileService(
                        repo,
                        accounts,
                        blobs,
                        manager,
                        mock(com.lumira.platform.common.AuditPort.class),
                        new FileQuota(Long.MAX_VALUE, 1000));
        var error = assertThrows(ResponseStatusException.class, () -> service.preview("actor", id));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(blobs, never()).open(any());
    }

    @Test
    void previewRejectsTrashedFileBeforeOpeningBlob() throws Exception {
        var repo = mock(FileRepository.class);
        var accounts = mock(com.lumira.platform.identity.api.AccountOwnerPort.class);
        var blobs = mock(BlobStore.class);
        var manager = mock(PlatformTransactionManager.class);
        var owner = UUID.randomUUID();
        var id = UUID.randomUUID();
        when(accounts.id("actor", false)).thenReturn(owner);
        when(repo.findOwned(id, owner)).thenReturn(Optional.of(entry(id, owner, "TRASHED", 4)));
        var service =
                new FileService(
                        repo,
                        accounts,
                        blobs,
                        manager,
                        mock(com.lumira.platform.common.AuditPort.class),
                        new FileQuota(Long.MAX_VALUE, 1000));
        var error = assertThrows(ResponseStatusException.class, () -> service.preview("actor", id));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("fileNotReady", error.getReason());
        verify(blobs, never()).open(any());
    }

    @Test
    void staleTrashVersionCannotRestore() {
        var repo = mock(FileRepository.class);
        var accounts = mock(com.lumira.platform.identity.api.AccountOwnerPort.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var owner = UUID.randomUUID();
        var id = UUID.randomUUID();
        when(accounts.id("actor", true)).thenReturn(owner);
        when(repo.findOwned(id, owner)).thenReturn(Optional.of(entry(id, owner, "TRASHED", 8)));
        var service =
                new FileService(
                        repo,
                        accounts,
                        mock(BlobStore.class),
                        manager,
                        mock(com.lumira.platform.common.AuditPort.class),
                        new FileQuota(Long.MAX_VALUE, 1000));
        assertThrows(ResponseStatusException.class, () -> service.restore("actor", id, 7));
        verify(repo, never()).restoreOwned(any(), any(), anyLong());
    }

    @Test
    void managedTrashRequiresFileDeleteOperation() {
        var repo = mock(FileRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var id = UUID.randomUUID();
        var owner = UUID.randomUUID();
        when(repo.findManaged(id))
                .thenReturn(
                        Optional.of(
                                new FileRepository.ManagedEntry(
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
                new FileService(
                        repo,
                        mock(com.lumira.platform.identity.api.AccountOwnerPort.class),
                        mock(BlobStore.class),
                        manager,
                        mock(com.lumira.platform.common.AuditPort.class),
                        new FileQuota(Long.MAX_VALUE, 1000),
                        guard);
        service.deleteManaged("admin", id, 4);
        verify(guard).requireOperationGlobal("admin", PlatformOperation.FILE_DELETE);
        verify(repo).requestManagedTrash(id, 4);
    }

    @Test
    void managedReadsRequireTheFilesAllPage() {
        var repo = mock(FileRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        var id = UUID.randomUUID();
        var service =
                new FileService(
                        repo,
                        mock(com.lumira.platform.identity.api.AccountOwnerPort.class),
                        mock(BlobStore.class),
                        mock(PlatformTransactionManager.class),
                        mock(com.lumira.platform.common.AuditPort.class),
                        new FileQuota(Long.MAX_VALUE, 1000),
                        guard);

        service.listManaged("admin", new PageRequest(1, 10, ""));

        verify(guard).requirePageGlobal("admin", PlatformPageId.FILES_ALL);
        verify(guard, never()).requireOperationGlobal(anyString(), any());
    }

    @Test
    void managedDownloadRequiresDownloadOperationForMetadataAndContent() throws Exception {
        var repo = mock(FileRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireOperationGlobal("admin", PlatformOperation.FILE_DOWNLOAD);
        var service =
                new FileService(
                        repo,
                        mock(com.lumira.platform.identity.api.AccountOwnerPort.class),
                        mock(BlobStore.class),
                        mock(PlatformTransactionManager.class),
                        mock(com.lumira.platform.common.AuditPort.class),
                        new FileQuota(Long.MAX_VALUE, 1000),
                        guard);

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
        var repo = mock(FileRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        var blobs = mock(BlobStore.class);
        var id = UUID.randomUUID();
        var owner = UUID.randomUUID();
        when(repo.findManaged(id))
                .thenReturn(
                        Optional.of(
                                new FileRepository.ManagedEntry(
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
        when(blobs.open(id)).thenReturn(new ByteArrayInputStream("preview".getBytes()));
        var service =
                new FileService(
                        repo,
                        mock(com.lumira.platform.identity.api.AccountOwnerPort.class),
                        blobs,
                        mock(PlatformTransactionManager.class),
                        mock(com.lumira.platform.common.AuditPort.class),
                        new FileQuota(Long.MAX_VALUE, 1000),
                        guard);

        var preview = service.previewManaged("admin", id, 1);

        assertEquals("preview", preview.text());
        verify(guard).requireOperationGlobal("admin", PlatformOperation.FILE_PREVIEW);
        verify(guard, never()).requireOperationGlobal("admin", PlatformOperation.FILE_DOWNLOAD);
    }

    @Test
    void downloadGrantAloneDoesNotAuthorizeManagedPreview() throws Exception {
        var repo = mock(FileRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(guard)
                .requireOperationGlobal("admin", PlatformOperation.FILE_PREVIEW);
        var service =
                new FileService(
                        repo,
                        mock(com.lumira.platform.identity.api.AccountOwnerPort.class),
                        mock(BlobStore.class),
                        mock(PlatformTransactionManager.class),
                        mock(com.lumira.platform.common.AuditPort.class),
                        new FileQuota(Long.MAX_VALUE, 1000),
                        guard);

        assertThrows(
                ResponseStatusException.class,
                () -> service.previewManaged("admin", UUID.randomUUID(), 1));

        verify(guard).requireOperationGlobal("admin", PlatformOperation.FILE_PREVIEW);
        verify(guard, never()).requireOperationGlobal("admin", PlatformOperation.FILE_DOWNLOAD);
        verify(repo, never()).findManaged(any());
    }

    @Test
    void managedRestoreRequiresRestoreOperationWithoutDeleteGrant() {
        var repo = mock(FileRepository.class);
        var guard = mock(com.lumira.platform.common.AdministrationPort.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var id = UUID.randomUUID();
        var owner = UUID.randomUUID();
        when(repo.findManaged(id))
                .thenReturn(
                        Optional.of(
                                new FileRepository.ManagedEntry(
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
                new FileService(
                        repo,
                        mock(com.lumira.platform.identity.api.AccountOwnerPort.class),
                        mock(BlobStore.class),
                        manager,
                        mock(com.lumira.platform.common.AuditPort.class),
                        new FileQuota(Long.MAX_VALUE, 1000),
                        guard);

        service.restoreManaged("admin", id, 4);

        verify(guard).requireOperationGlobal("admin", PlatformOperation.FILE_RESTORE);
        verify(guard, never()).requireOperationGlobal("admin", PlatformOperation.FILE_DELETE);
        verify(repo).restoreManaged(id, 4);
    }
}
