package com.lumira.platform.common;

import java.util.*;

/** Exact existing editor grants retain their original delegation and ownership. */
public final class AuthenticationConsoleAuthorityCodes {
    private static final Set<PlatformPageId> PAGES =
            Set.of(
                    PlatformPageId.SECURITY,
                    PlatformPageId.AUTHENTICATION,
                    PlatformPageId.PASSKEY,
                    PlatformPageId.WECHAT,
                    PlatformPageId.CAPTCHA,
                    PlatformPageId.REGISTRATION);
    private static final Set<String> VALUES = codes();

    private static Set<String> codes() {
        var values = new HashSet<String>();
        for (var page : PAGES) values.add(page.accessPermission());
        for (var operation : PlatformOperation.values())
            if (PAGES.contains(operation.page())) values.add(operation.permission());
        return Set.copyOf(values);
    }

    public static Set<String> values() {
        return VALUES;
    }

    private AuthenticationConsoleAuthorityCodes() {}
}
