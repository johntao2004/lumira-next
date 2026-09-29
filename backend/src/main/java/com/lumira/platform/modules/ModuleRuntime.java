package com.lumira.platform.modules;

/** Read-only lifecycle contract for HTTP adapters and background jobs. */
@FunctionalInterface
public interface ModuleRuntime {
    boolean isEnabled(String moduleId);
}
