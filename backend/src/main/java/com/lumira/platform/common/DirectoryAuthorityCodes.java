package com.lumira.platform.common;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Exact legacy directory grants retained when their implementation becomes an optional plugin. */
public final class DirectoryAuthorityCodes {
    private static final Set<PlatformPageId> PAGES =
            Set.of(
                    PlatformPageId.USERS,
                    PlatformPageId.ONLINE_USERS,
                    PlatformPageId.ROLES,
                    PlatformPageId.ORGANIZATIONS,
                    PlatformPageId.PRINT_TEMPLATES);
    private static final Set<String> VALUES =
            Stream.concat(
                            PAGES.stream().map(PlatformPageId::accessPermission),
                            Arrays.stream(PlatformOperation.values())
                                    .filter(operation -> PAGES.contains(operation.page()))
                                    .map(PlatformOperation::permission))
                    .collect(Collectors.toUnmodifiableSet());

    public static Set<String> values() {
        return VALUES;
    }

    private DirectoryAuthorityCodes() {}
}
