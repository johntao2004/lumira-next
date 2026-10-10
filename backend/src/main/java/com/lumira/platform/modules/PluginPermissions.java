package com.lumira.platform.modules;

import java.util.*;

public final class PluginPermissions {
    private static final Set<String> values = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void register(Collection<String> permissions) {
        values.addAll(permissions);
    }

    public static Set<String> values() {
        return Set.copyOf(values);
    }

    private PluginPermissions() {}
}
