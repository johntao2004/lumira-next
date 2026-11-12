package com.lumira.platform.common;

import java.util.*;
import java.util.stream.*;

/**
 * Exact legacy profile administration grants retain ordinary global delegation after extraction.
 */
public final class ProfileConsoleAuthorityCodes {
    private static final Set<PlatformPageId> PAGES = Set.of(PlatformPageId.PROFILE_FIELDS);
    private static final Set<String> VALUES =
            Stream.concat(
                            PAGES.stream().map(PlatformPageId::accessPermission),
                            Arrays.stream(PlatformOperation.values())
                                    .filter(op -> PAGES.contains(op.page()))
                                    .map(PlatformOperation::permission))
                    .collect(Collectors.toUnmodifiableSet());

    public static Set<String> values() {
        return VALUES;
    }

    private ProfileConsoleAuthorityCodes() {}
}
