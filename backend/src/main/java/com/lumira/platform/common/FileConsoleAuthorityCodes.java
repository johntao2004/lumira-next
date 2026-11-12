package com.lumira.platform.common;

import java.util.*;
import java.util.stream.*;

/** Exact legacy file-center grants retain ordinary global delegation after extraction. */
public final class FileConsoleAuthorityCodes {
    private static final Set<String> VALUES =
            Stream.concat(
                            Stream.of(PlatformPageId.FILES_ALL.accessPermission()),
                            Arrays.stream(PlatformOperation.values())
                                    .filter(op -> op.page() == PlatformPageId.FILES_ALL)
                                    .map(PlatformOperation::permission))
                    .collect(Collectors.toUnmodifiableSet());

    public static Set<String> values() {
        return VALUES;
    }

    private FileConsoleAuthorityCodes() {}
}
