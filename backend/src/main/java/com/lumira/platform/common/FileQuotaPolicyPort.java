package com.lumira.platform.common;

import java.util.Collection;
import java.util.UUID;

/** Stable policy boundary from file handling to role-owned quota configuration. */
public interface FileQuotaPolicyPort {
    /** Zero bytes means unlimited aggregate storage; zero files means no file slots. */
    record Limits(long maximumBytes, long maximumFiles) {}

    Limits forUser(UUID userId);

    Limits forRoles(Collection<UUID> roleIds);
}
