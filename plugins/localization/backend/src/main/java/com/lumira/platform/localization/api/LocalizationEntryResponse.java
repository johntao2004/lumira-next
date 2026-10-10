package com.lumira.platform.localization.api;

import com.lumira.platform.localization.LocalizationRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Explicit HTTP response; persistence records remain internal to the feature. */
public record LocalizationEntryResponse(
        UUID id,
        String messageKey,
        String namespace,
        String defaultMessage,
        Map<String, String> translations,
        boolean enabled,
        long version,
        String createdBy,
        String updatedBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
    public static LocalizationEntryResponse from(LocalizationRepository.Entry value) {
        if (value == null) return null;
        return new LocalizationEntryResponse(
                value.id(),
                value.messageKey(),
                value.namespace(),
                value.defaultMessage(),
                value.translations(),
                value.enabled(),
                value.version(),
                value.createdBy(),
                value.updatedBy(),
                value.createdAt(),
                value.updatedAt());
    }
}
