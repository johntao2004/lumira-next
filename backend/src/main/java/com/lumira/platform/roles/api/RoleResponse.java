package com.lumira.platform.roles.api;

import com.lumira.platform.roles.RoleRepository;
import java.util.*;

/** Explicit HTTP response; persistence records remain internal to the feature. */
public record RoleResponse(
        UUID id,
        String code,
        String name,
        boolean enabled,
        long version,
        String dataScope,
        Long fileQuotaBytesLimit,
        Long fileQuotaFilesLimit,
        List<String> authorities,
        boolean defaultRegistrationRole) {
    public RoleResponse {
        authorities = List.copyOf(authorities == null ? List.of() : authorities);
    }

    public static RoleResponse from(RoleRepository.Role value) {
        if (value == null) return null;
        return new RoleResponse(
                value.id(),
                value.code(),
                value.name(),
                value.enabled(),
                value.version(),
                value.dataScope(),
                value.fileQuotaBytesLimit(),
                value.fileQuotaFilesLimit(),
                value.authorities(),
                value.defaultRegistrationRole());
    }
}
