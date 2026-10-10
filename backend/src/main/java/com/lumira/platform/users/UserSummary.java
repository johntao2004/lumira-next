package com.lumira.platform.users;

import java.util.UUID;

/** Never serialize password hashes or other account credentials. */
public record UserSummary(
        UUID id,
        String username,
        String displayName,
        boolean enabled,
        long version,
        Long fileQuotaBytesOverride,
        Long fileQuotaFilesOverride) {}
