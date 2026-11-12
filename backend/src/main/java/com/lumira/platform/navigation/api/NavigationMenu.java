package com.lumira.platform.navigation.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Shared navigation value; independent of the JDBC adapter and administrative plugin. */
public record NavigationMenu(
        UUID id,
        UUID parentId,
        String menuCode,
        String labelKey,
        String labelDefault,
        String menuType,
        String sectionKey,
        String path,
        String componentKey,
        String icon,
        String permissionKey,
        boolean globalOnly,
        String moduleId,
        int sortOrder,
        boolean enabled,
        boolean builtIn,
        boolean deleted,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
