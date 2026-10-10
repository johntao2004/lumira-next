package com.lumira.platform.files.api;

import com.lumira.platform.files.FileRepository;
import java.time.Instant;
import java.util.UUID;

/** Explicit HTTP response; persistence records remain internal to the feature. */
public record ManagedFileResponse(
        UUID id,
        UUID ownerId,
        String ownerUsername,
        String ownerDisplayName,
        String storageBackend,
        String originalName,
        String state,
        Long sizeBytes,
        String sha256,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant trashedAt,
        boolean protectedByReference) {
    public static ManagedFileResponse from(FileRepository.ManagedEntry value) {
        if (value == null) return null;
        return new ManagedFileResponse(
                value.id(),
                value.ownerId(),
                value.ownerUsername(),
                value.ownerDisplayName(),
                value.storageBackend(),
                value.originalName(),
                value.state(),
                value.sizeBytes(),
                value.sha256(),
                value.version(),
                value.createdAt(),
                value.updatedAt(),
                value.trashedAt(),
                value.protectedByReference());
    }
}
