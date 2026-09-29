package com.lumira.platform.common;

import java.util.Set;
import java.util.UUID;

/**
 * Stable authorization contract for application modules. The JDBC-backed
 * implementation remains in identity; consumers do not depend on that class.
 */
public interface AdministrationPort {
    Set<String> require(String actor, String permission);

    void requireUser(String actor, UUID target, String permission);

    void requireGlobal(String actor, String permission);

    boolean hasGlobal(String actor, String permission);
}
