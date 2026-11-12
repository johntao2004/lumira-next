package com.lumira.platform.common;

import java.util.Set;
import java.util.UUID;

/** Decides which roles may be used to explain a proposed assignment. */
public interface RoleAssignmentPreviewPort {
    void requirePreviewable(String actor, UUID userId, Set<UUID> roleIds);
}
