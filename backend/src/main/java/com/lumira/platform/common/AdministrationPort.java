package com.lumira.platform.common;

import java.util.Set;
import java.util.UUID;

/**
 * Stable authorization contract for application modules. The JDBC-backed implementation remains in
 * identity; consumers do not depend on that class.
 */
public interface AdministrationPort {
    Set<String> require(String actor, String permission);

    void requireUser(String actor, UUID target, String permission);

    void requireGlobal(String actor, String permission);

    boolean hasGlobal(String actor, String permission);

    default Set<String> requirePage(String actor, PlatformPageId page) {
        Set<String> held = require(actor, page.accessPermission());
        if (page.scope() == PlatformOperation.Scope.GLOBAL)
            requireGlobal(actor, page.accessPermission());
        return held;
    }

    default Set<String> requirePageGlobal(String actor, PlatformPageId page) {
        Set<String> held = require(actor, page.accessPermission());
        requireGlobal(actor, page.accessPermission());
        return held;
    }

    /** An operation is effective only when both its owning page and its own action are granted. */
    default Set<String> requireOperation(String actor, PlatformOperation operation) {
        Set<String> held = new java.util.HashSet<>(requirePage(actor, operation.page()));
        held.addAll(require(actor, operation.permission()));
        if (operation.scope() == PlatformOperation.Scope.GLOBAL)
            requireGlobal(actor, operation.permission());
        return Set.copyOf(held);
    }

    default Set<String> requireOperationGlobal(String actor, PlatformOperation operation) {
        Set<String> held = requireOperation(actor, operation);
        requireGlobal(actor, operation.page().accessPermission());
        requireGlobal(actor, operation.permission());
        return held;
    }

    /** A globally scoped action on a page whose own target visibility may be scoped. */
    default Set<String> requireOperationActionGlobal(String actor, PlatformOperation operation) {
        Set<String> held = requireOperation(actor, operation);
        requireGlobal(actor, operation.permission());
        return held;
    }

    default void requireOperationUser(String actor, UUID target, PlatformOperation operation) {
        requireOperation(actor, operation);
        requireUser(actor, target, operation.page().accessPermission());
        requireUser(actor, target, operation.permission());
    }
}
