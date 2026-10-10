package com.lumira.platform.common;

/** Stable language-registration boundary; the host does not read plugin-owned tables. */
public interface LocaleRegistryPort {
    boolean enabled(String locale);
}
