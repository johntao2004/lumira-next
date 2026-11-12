package com.lumira.plugins.personalfiles;

import com.lumira.platform.common.AccountFileStoragePort;
import java.time.Instant;
import java.util.UUID;

/** Explicit HTTP response; persistence records remain internal to the feature. */
public record FileResponse(
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
        Instant trashedAt) {
    public static FileResponse from(AccountFileStoragePort.Entry value) {
        if (value == null) return null;
        return new FileResponse(
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
}
