package com.lumira.platform.common;

import java.util.Set;
import java.util.UUID;

/** Roles-owned allow-list and assignment operations for the single public-registration role. */
public interface RegistrationRolePort {
    Set<String> SELF_SERVICE_AUTHORITIES =
            Set.of(
                    "platform:feedback:access",
                    "platform:feedback:read",
                    "platform:feedback:create");

    PageResult<Candidate> candidates(PageRequest page);

    Candidate candidate(UUID roleId);

    /**
     * Hold a shared role-row lock and fail unless it remains enabled, SELF-scoped and allow-listed.
     */
    void lockAndRequireEligible(UUID roleId);

    /** Revalidate under a shared role-row lock and assign inside the caller transaction. */
    void assignToNewAccount(UUID accountId, UUID roleId);

    boolean isDefaultRole(UUID roleId);

    record Candidate(
            UUID id, String code, String name, long version, boolean eligible, String reason) {}
}
