package com.lumira.platform.common;

import java.util.Set;

/** Read-only authorization for dynamically registered endpoints. */
public interface PluginAuthorizationPort {
    boolean authorize(String method, String path, Set<String> authorities, boolean authenticated);

    /** True only for an enabled plugin API rule explicitly marked side-effect-free for preview. */
    default boolean previewReadOnly(
            String method,
            String path,
            Set<String> effectiveAuthorities,
            Set<String> effectiveGlobalAuthorities) {
        return false;
    }

    /** Exact digest-bound static frontend assets may be loaded with a revision query parameter. */
    default boolean safeFrontendAsset(String path) {
        return false;
    }
}
