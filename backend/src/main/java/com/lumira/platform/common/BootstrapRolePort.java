package com.lumira.platform.common;

import java.util.UUID;

/** Narrow bootstrap capability for assigning the platform's initial administrator role. */
public interface BootstrapRolePort {
    void assignAdministratorRole(UUID userId);
}
