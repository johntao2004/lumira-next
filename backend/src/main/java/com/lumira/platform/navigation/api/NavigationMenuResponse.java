package com.lumira.platform.navigation.api;

import java.time.OffsetDateTime;
import java.util.*;

/** Explicit HTTP response; persistence records remain internal to the feature. */
public record NavigationMenuResponse(
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
        OffsetDateTime updatedAt) {
    public static NavigationMenuResponse from(NavigationMenu value) {
        if (value == null) return null;
        return new NavigationMenuResponse(
                value.id(),
                value.parentId(),
                value.menuCode(),
                value.labelKey(),
                value.labelDefault(),
                value.menuType(),
                value.sectionKey(),
                value.path(),
                value.componentKey(),
                value.icon(),
                value.permissionKey(),
                value.globalOnly(),
                value.moduleId(),
                value.sortOrder(),
                value.enabled(),
                value.builtIn(),
                value.deleted(),
                value.version(),
                value.createdAt(),
                value.updatedAt());
    }
}
