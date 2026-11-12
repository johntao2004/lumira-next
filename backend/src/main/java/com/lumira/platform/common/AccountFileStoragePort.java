package com.lumira.platform.common;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Required metadata/blob primitives. Personal file workflows belong to their optional plugin. */
public interface AccountFileStoragePort {
    long MAXIMUM_BYTES = 20L * 1024 * 1024;

    final class TooLarge extends IOException {}

    record Entry(
            UUID id,
            UUID ownerId,
            String storageBackend,
            String originalName,
            String state,
            Long sizeBytes,
            String sha256,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant trashedAt) {}

    record Usage(
            long chargedBytes,
            long fileCount,
            long pendingDeletingCount,
            long pendingDeletingBytes) {}

    record QuotaOwner(UUID userId, String username, String displayName, long version) {}

    QuotaOwner quotaOwner(UUID userId);

    Entry upload(String actor, String filename, InputStream source, long contentLength)
            throws IOException;

    Optional<Entry> findOwned(UUID id, UUID owner);

    PageResult<Entry> listOwned(UUID owner, PageRequest page, boolean trash);

    Usage usage(UUID owner);

    boolean requestTrash(UUID id, UUID owner, long version);

    boolean restoreOwned(UUID id, UUID owner, long version);

    boolean purgeOwned(UUID id, UUID owner, long version);
}
