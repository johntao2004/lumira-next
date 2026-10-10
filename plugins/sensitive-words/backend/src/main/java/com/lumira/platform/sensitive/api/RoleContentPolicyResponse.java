package com.lumira.platform.sensitive.api;

import com.lumira.platform.sensitive.RoleContentPolicyRepository;
import java.util.UUID;

/** Explicit HTTP response; persistence records remain internal to the feature. */
public record RoleContentPolicyResponse(
        UUID roleId,
        String roleCode,
        String roleName,
        boolean roleEnabled,
        String mode,
        long version) {
    public static RoleContentPolicyResponse from(RoleContentPolicyRepository.Policy value) {
        if (value == null) return null;
        return new RoleContentPolicyResponse(
                value.roleId(),
                value.roleCode(),
                value.roleName(),
                value.roleEnabled(),
                value.mode(),
                value.version());
    }
}
