package com.lumira.platform.modules;

import java.util.Set;

/**
 * Authorization port used by module domain services.
 *
 * <p>The identity adapter supplies the implementation; keeping the port here prevents module
 * lifecycle code from depending on HTTP or identity packages.</p>
 */
public interface ModuleAdministrationAuthorizer {
    Set<String> require(String actor, String permission);

    void requireGlobal(String actor, String permission);

    boolean hasGlobal(String actor, String permission);
}
