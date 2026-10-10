package com.lumira.platform.sensitive.api;

import com.lumira.platform.sensitive.SensitiveWordRepository;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Explicit HTTP response; persistence records remain internal to the feature. */
public record SensitiveWordResponse(
        UUID id,
        String word,
        String normalizedWord,
        String category,
        String severity,
        String action,
        boolean enabled,
        long version,
        String createdBy,
        String updatedBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
    public static SensitiveWordResponse from(SensitiveWordRepository.Word value) {
        if (value == null) return null;
        return new SensitiveWordResponse(
                value.id(),
                value.word(),
                value.normalizedWord(),
                value.category(),
                value.severity(),
                value.action(),
                value.enabled(),
                value.version(),
                value.createdBy(),
                value.updatedBy(),
                value.createdAt(),
                value.updatedAt());
    }
}
