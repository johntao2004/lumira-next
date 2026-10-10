package com.lumira.platform.organizations.api;

import com.lumira.platform.organizations.OrganizationRepository;
import java.util.*;

/** Explicit HTTP response; persistence records remain internal to the feature. */
public record OrganizationResponse(
        UUID id,
        UUID parentId,
        String code,
        String name,
        boolean enabled,
        int sortNo,
        long version) {
    public OrganizationResponse(
            UUID id, UUID parentId, String code, String name, boolean enabled, long version) {
        this(id, parentId, code, name, enabled, 0, version);
    }

    public static OrganizationResponse from(OrganizationRepository.Organization value) {
        if (value == null) return null;
        return new OrganizationResponse(
                value.id(),
                value.parentId(),
                value.code(),
                value.name(),
                value.enabled(),
                value.sortNo(),
                value.version());
    }
}
