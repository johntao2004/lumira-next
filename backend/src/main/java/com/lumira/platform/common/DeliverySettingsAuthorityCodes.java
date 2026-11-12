package com.lumira.platform.common;

import java.util.*;
import java.util.stream.*;

/**
 * Exact legacy delivery settings administration grants retain ordinary global delegation after
 * extraction.
 */
public final class DeliverySettingsAuthorityCodes {
    private static final Set<PlatformPageId> PAGES =
            Set.of(PlatformPageId.SMTP, PlatformPageId.SMS);
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

    private DeliverySettingsAuthorityCodes() {}
}
