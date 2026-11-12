package com.lumira.plugins.directory.sessions.api;

import com.lumira.plugins.directory.sessions.OnlineSessionRepository;
import java.time.Instant;
import java.util.*;

/** Explicit HTTP response; persistence records remain internal to the feature. */
public record OnlineSessionResponse(
        UUID id,
        UUID userId,
        String username,
        String displayName,
        boolean enabled,
        Instant createdAt,
        Instant lastAccessAt,
        Instant expiresAt) {
    public static OnlineSessionResponse from(OnlineSessionRepository.Entry value) {
        if (value == null) return null;
        return new OnlineSessionResponse(
                value.id(),
                value.userId(),
                value.username(),
                value.displayName(),
                value.enabled(),
                value.createdAt(),
                value.lastAccessAt(),
                value.expiresAt());
    }
}
