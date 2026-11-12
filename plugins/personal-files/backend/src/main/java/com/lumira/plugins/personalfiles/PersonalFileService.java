package com.lumira.plugins.personalfiles;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.AccountOwnerPort;
import java.io.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Optional account workflow: owner boundaries, read snapshots, lifecycle CAS and audit. */
@Service
public class PersonalFileService {
    public record Usage(
            long chargedBytes,
            long fileCount,
            long maximumBytes,
            long maximumFiles,
            long pendingDeletingCount,
            long pendingDeletingBytes) {}

    private final AccountFileStoragePort files;
    private final FileManagementStoragePort payloads;
    private final AccountOwnerPort accounts;
    private final FileQuotaPolicyPort quotas;
    private final AuditPort audit;
    private final AuthorizationContextBoundaryPort authorization;
    private final TransactionTemplate transaction;

    public PersonalFileService(
            AccountFileStoragePort files,
            FileManagementStoragePort payloads,
            AccountOwnerPort accounts,
            FileQuotaPolicyPort quotas,
            AuditPort audit,
            AuthorizationContextBoundaryPort authorization,
            PlatformTransactionManager manager) {
        this.files = files;
        this.payloads = payloads;
        this.accounts = accounts;
        this.quotas = quotas;
        this.audit = audit;
        this.authorization = authorization;
        this.transaction = new TransactionTemplate(manager);
    }

    public AccountFileStoragePort.Entry upload(
            String actor, String filename, InputStream source, long contentLength)
            throws IOException {
        return files.upload(actor, filename, source, contentLength);
    }

    public AccountFileStoragePort.Entry find(String actor, UUID id) {
        return files.findOwned(id, accounts.id(actor, false))
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<AccountFileStoragePort.Entry> list(String actor, PageRequest page) {
        return files.listOwned(accounts.id(actor, false), page, false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<AccountFileStoragePort.Entry> listTrash(String actor, PageRequest page) {
        return files.listOwned(accounts.id(actor, false), page, true);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Usage usage(String actor) {
        UUID owner = accounts.id(actor, false);
        var used = files.usage(owner);
        var maximum = quotas.forUser(owner);
        return new Usage(
                used.chargedBytes(),
                used.fileCount(),
                maximum.maximumBytes(),
                maximum.maximumFiles(),
                used.pendingDeletingCount(),
                used.pendingDeletingBytes());
    }

    public InputStream open(AccountFileStoragePort.Entry entry) throws IOException {
        return payloads.open(blob(entry));
    }

    public FileManagementStoragePort.FilePreviewView preview(String actor, UUID id, int page)
            throws IOException {
        return payloads.preview(blob(find(actor, id)), page);
    }

    public void delete(String actor, UUID id, long version) {
        transaction.executeWithoutResult(
                status -> {
                    validateAuthorizationBoundary();
                    var entry = find(actor, id);
                    if (entry.version() != version
                            || !files.requestTrash(id, entry.ownerId(), version)) throw conflict();
                    audit.append(actor, "file.trash", id);
                });
    }

    public void restore(String actor, UUID id, long version) {
        transaction.executeWithoutResult(
                status -> {
                    validateAuthorizationBoundary();
                    UUID owner = accounts.id(actor, true);
                    var entry =
                            files.findOwned(id, owner)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND, "fileNotFound"));
                    if (entry.version() != version || !files.restoreOwned(id, owner, version))
                        throw conflict();
                    audit.append(actor, "file.restore", id);
                });
    }

    public void purge(String actor, UUID id, long version) {
        transaction.executeWithoutResult(
                status -> {
                    validateAuthorizationBoundary();
                    UUID owner = accounts.id(actor, true);
                    var entry =
                            files.findOwned(id, owner)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND, "fileNotFound"));
                    if (entry.version() != version || !files.purgeOwned(id, owner, version))
                        throw conflict();
                    audit.append(actor, "file.purgeRequested", id);
                });
        payloads.finishDeletion(id, version + 1);
    }

    private void validateAuthorizationBoundary() {
        if (authorization != null) authorization.lockAndValidateCurrentRequest();
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "fileStateConflict");
    }

    private static FileManagementStoragePort.Blob blob(AccountFileStoragePort.Entry entry) {
        return new FileManagementStoragePort.Blob(
                entry.id(), entry.originalName(), entry.state(), entry.sizeBytes());
    }
}
