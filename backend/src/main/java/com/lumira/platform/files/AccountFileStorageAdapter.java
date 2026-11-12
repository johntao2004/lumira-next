package com.lumira.platform.files;

import com.lumira.platform.common.AccountFileStoragePort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Shared storage primitives retain the existing row, quota reservation and blob protocol. */
@Service
public class AccountFileStorageAdapter implements AccountFileStoragePort {
    private final FileRepository files;
    private final FileService uploads;

    public AccountFileStorageAdapter(FileRepository files, FileService uploads) {
        this.files = files;
        this.uploads = uploads;
    }

    private static Entry entry(FileRepository.Entry value) {
        return new Entry(
                value.id(),
                value.ownerId(),
                value.storageBackend(),
                value.originalName(),
                value.state(),
                value.sizeBytes(),
                value.sha256(),
                value.version(),
                value.createdAt(),
                value.updatedAt(),
                value.trashedAt());
    }

    @Override
    public Entry upload(String actor, String filename, InputStream source, long contentLength)
            throws IOException {
        try {
            return entry(uploads.upload(actor, filename, source, contentLength));
        } catch (BlobStore.TooLarge tooLarge) {
            throw new AccountFileStoragePort.TooLarge();
        }
    }

    @Override
    public QuotaOwner quotaOwner(UUID userId) {
        var owner = files.quotaOwner(userId, false);
        return new QuotaOwner(
                owner.userId(), owner.username(), owner.displayName(), owner.version());
    }

    @Override
    public Optional<Entry> findOwned(UUID id, UUID owner) {
        return files.findOwned(id, owner).map(AccountFileStorageAdapter::entry);
    }

    @Override
    public PageResult<Entry> listOwned(UUID owner, PageRequest page, boolean trash) {
        return (trash ? files.listTrashOwned(owner, page) : files.listOwned(owner, page))
                .map(AccountFileStorageAdapter::entry);
    }

    @Override
    public Usage usage(UUID owner) {
        var value = files.usage(owner);
        return new Usage(
                value.chargedBytes(),
                value.fileCount(),
                value.pendingDeletingCount(),
                value.pendingDeletingBytes());
    }

    @Override
    public boolean requestTrash(UUID id, UUID owner, long version) {
        return files.requestTrash(id, owner, version);
    }

    @Override
    public boolean restoreOwned(UUID id, UUID owner, long version) {
        return files.restoreOwned(id, owner, version);
    }

    @Override
    public boolean purgeOwned(UUID id, UUID owner, long version) {
        return files.purgeOwned(id, owner, version);
    }
}
