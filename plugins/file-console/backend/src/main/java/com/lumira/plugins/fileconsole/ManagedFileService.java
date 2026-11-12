package com.lumira.plugins.fileconsole;

import com.lumira.platform.common.*;
import java.io.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ManagedFileService {
    private final ManagedFileRepository files;
    private final FileManagementStoragePort storage;
    private final AuditPort audit;
    private final AdministrationPort guard;
    private final TransactionTemplate transaction;
    private final AuthorizationContextBoundaryPort authorizationBoundary;

    public ManagedFileService(
            ManagedFileRepository files,
            FileManagementStoragePort storage,
            PlatformTransactionManager manager,
            AuditPort audit,
            AdministrationPort guard,
            AuthorizationContextBoundaryPort authorizationBoundary) {
        this.files = files;
        this.storage = storage;
        this.audit = audit;
        this.guard = guard;
        this.transaction = new TransactionTemplate(manager);
        this.authorizationBoundary = authorizationBoundary;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<ManagedFileRepository.Entry> listManaged(String actor, PageRequest page) {
        guard.requirePageGlobal(actor, PlatformPageId.FILES_ALL);
        return files.listManaged(page);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<ManagedFileRepository.Entry> listManagedTrash(
            String actor, PageRequest page) {
        guard.requirePageGlobal(actor, PlatformPageId.FILES_ALL);
        return files.listManagedTrash(page);
    }

    public ManagedFileRepository.Entry findManaged(String actor, UUID id) {
        guard.requireOperationGlobal(actor, PlatformOperation.FILE_DOWNLOAD);
        return managedEntry(id);
    }

    private ManagedFileRepository.Entry managedEntry(UUID id) {
        return files.findManaged(id)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound"));
    }

    public InputStream openManaged(String actor, UUID id) throws IOException {
        guard.requireOperationGlobal(actor, PlatformOperation.FILE_DOWNLOAD);
        return storage.open(blob(managedEntry(id)));
    }

    public void deleteManaged(String actor, UUID id, long version) {
        transaction.executeWithoutResult(
                status -> {
                    guard.requireConcurrentOperationGlobal(actor, PlatformOperation.FILE_DELETE);
                    validateAuthorizationBoundary();
                    var entry =
                            files.findManaged(id)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND, "fileNotFound"));
                    if (entry.version() != version)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "fileStateConflict");
                    if (!files.requestManagedTrash(id, version))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "fileStateConflict");
                    audit.append(actor, "file.trash", id);
                });
    }

    public void restoreManaged(String actor, UUID id, long version) {
        transaction.executeWithoutResult(
                status -> {
                    guard.requireConcurrentOperationGlobal(actor, PlatformOperation.FILE_RESTORE);
                    validateAuthorizationBoundary();
                    var entry =
                            files.findManaged(id)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND, "fileNotFound"));
                    if (entry.version() != version || !files.restoreManaged(id, version))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "fileStateConflict");
                    audit.append(actor, "file.restore", id);
                });
    }

    public void purgeManaged(String actor, UUID id, long version) {
        transaction.executeWithoutResult(
                status -> {
                    guard.requireConcurrentOperationGlobal(actor, PlatformOperation.FILE_PURGE);
                    validateAuthorizationBoundary();
                    var entry =
                            files.findManaged(id)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND, "fileNotFound"));
                    if (entry.version() != version || !files.purgeManaged(id, version))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "fileStateConflict");
                    audit.append(actor, "file.purgeRequested", id);
                });
        storage.finishDeletion(id, version + 1);
    }

    public FileManagementStoragePort.FilePreviewView previewManaged(String actor, UUID id, int page)
            throws IOException {
        guard.requireOperationGlobal(actor, PlatformOperation.FILE_PREVIEW);
        return storage.preview(blob(managedEntry(id)), page);
    }

    private static FileManagementStoragePort.Blob blob(ManagedFileRepository.Entry entry) {
        return new FileManagementStoragePort.Blob(
                entry.id(), entry.originalName(), entry.state(), entry.sizeBytes());
    }

    private void validateAuthorizationBoundary() {
        authorizationBoundary.lockAndValidateCurrentRequest();
    }
}
