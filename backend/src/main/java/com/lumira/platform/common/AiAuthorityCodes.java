package com.lumira.platform.common;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Exact legacy AI grants retained when their implementation becomes an optional plugin. */
public final class AiAuthorityCodes {
    private static final Set<PlatformPageId> PAGES =
            Set.of(PlatformPageId.AI_CONSOLE, PlatformPageId.AI_MODELS);
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

    private AiAuthorityCodes() {}
}
