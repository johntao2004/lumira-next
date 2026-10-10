package com.lumira.platform.sensitive.api;

import com.lumira.platform.sensitive.RoleContentPolicyRepository;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Explicit HTTP response; persistence records remain internal to the feature. */
public record ContentPolicyHitResponse(
        UUID id,
        String actor,
        String source,
        String action,
        int matchCount,
        OffsetDateTime createdAt) {
    public static ContentPolicyHitResponse from(RoleContentPolicyRepository.Hit value) {
        if (value == null) return null;
        return new ContentPolicyHitResponse(
                value.id(),
                value.actor(),
                value.source(),
                value.action(),
                value.matchCount(),
                value.createdAt());
    }
}
