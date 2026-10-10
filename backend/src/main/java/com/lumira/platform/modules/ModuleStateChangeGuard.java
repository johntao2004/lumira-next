package com.lumira.platform.modules;

/** Runtime-specific gate for module lifecycle changes that require local plugin contexts. */
public interface ModuleStateChangeGuard {
    void requireChangeAllowed(String moduleId, boolean enabled);

    default void recordChange(String moduleId, boolean enabled, String actor) {}
}
