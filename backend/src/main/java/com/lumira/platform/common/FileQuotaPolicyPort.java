package com.lumira.platform.common;

import java.util.Collection;
import java.util.UUID;

/** Stable policy boundary from file handling to role-owned quota configuration. */
public interface FileQuotaPolicyPort {
    /** A zero value in either dimension means unlimited capacity for that dimension. */
    record Limits(long maximumBytes, long maximumFiles) {}

    Limits forUser(UUID userId);

    Limits forRoles(Collection<UUID> roleIds);
}
