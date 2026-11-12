package com.lumira.platform.common;

import java.util.Set;

/** Registered module identifiers, including installed and disabled business capabilities. */
public interface ModuleCatalogPort {
    Set<String> moduleIds();
}
