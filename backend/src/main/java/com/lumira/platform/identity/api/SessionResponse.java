package com.lumira.platform.identity.api;

import com.lumira.platform.identity.AccountSessionRepository;
import java.time.Instant;
import java.util.*;

/** Explicit HTTP response; persistence records remain internal to the feature. */
public record SessionResponse(
        String id, Instant createdAt, Instant lastAccessAt, Instant expiresAt, boolean current) {
    public static SessionResponse from(AccountSessionRepository.Session value) {
        if (value == null) return null;
        return new SessionResponse(
                value.id(),
                value.createdAt(),
                value.lastAccessAt(),
                value.expiresAt(),
                value.current());
    }
}
