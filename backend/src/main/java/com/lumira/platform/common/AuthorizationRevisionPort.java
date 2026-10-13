package com.lumira.platform.common;

import java.util.Set;
import java.util.UUID;

/**
 * Supplies an opaque session cache tag for the user's current authorization and data visibility.
 */
public interface AuthorizationRevisionPort {
    String revision(UUID userId, Set<String> effectiveAuthorities, Set<String> globalAuthorities);
}
