package com.lumira.platform.common;

import java.util.Set;
import java.util.UUID;

/**
 * 应用模块的授权契约；JDBC 实现留在 identity，调用方不依赖实现类。 Stable authorization contract for application modules.
 * The JDBC-backed implementation remains in identity; consumers do not depend on that class.
 */
public interface AdministrationPort {
    /**
     * 要求操作者具备指定权限并返回其权限集合；不满足则拒绝。 Requires the actor permission and returns held permissions;
     * denies otherwise.
     */
    Set<String> require(String actor, String permission);

    /** 要求操作者对目标用户具有指定权限及数据范围。 Requires permission and data scope for the target user. */
    void requireUser(String actor, UUID target, String permission);

    /**
     * 要求指定权限具有全局范围，范围不足则拒绝。 Requires global scope for the permission; denies insufficient scope.
     */
    void requireGlobal(String actor, String permission);

    /** 检查操作者是否具有该权限的全局范围。 Reports whether the actor holds global scope for the permission. */
    boolean hasGlobal(String actor, String permission);

    /**
     * 要求页面访问权限及页面声明的全局范围，返回已持有权限。 Requires page access and its declared global scope; returns held
     * permissions.
     */
    default Set<String> requirePage(String actor, PlatformPageId page) {
        Set<String> held = require(actor, page.accessPermission());
        if (page.scope() == PlatformOperation.Scope.GLOBAL)
            requireGlobal(actor, page.accessPermission());
        return held;
    }

    /**
     * 无论页面默认范围如何，都要求其访问权限具有全局范围。 Requires globally scoped page access regardless of the page
     * default scope.
     */
    default Set<String> requirePageGlobal(String actor, PlatformPageId page) {
        Set<String> held = require(actor, page.accessPermission());
        requireGlobal(actor, page.accessPermission());
        return held;
    }

    /**
     * 页面权限与动作权限同时满足，操作才生效。 An operation is effective only when both its owning page and its own
     * action are granted.
     */
    default Set<String> requireOperation(String actor, PlatformOperation operation) {
        Set<String> held = new java.util.HashSet<>(requirePage(actor, operation.page()));
        held.addAll(require(actor, operation.permission()));
        if (operation.scope() == PlatformOperation.Scope.GLOBAL)
            requireGlobal(actor, operation.permission());
        return Set.copyOf(held);
    }

    /**
     * 要求页面与动作均具有全局范围，返回检查所得权限。 Requires global scope for both page and action; returns the checked
     * permissions.
     */
    default Set<String> requireOperationGlobal(String actor, PlatformOperation operation) {
        Set<String> held = requireOperation(actor, operation);
        requireGlobal(actor, operation.page().accessPermission());
        requireGlobal(actor, operation.permission());
        return held;
    }

    /**
     * 动作要求全局权限，页面目标可见范围仍可受限。 A globally scoped action on a page whose own target visibility may be
     * scoped.
     */
    default Set<String> requireOperationActionGlobal(String actor, PlatformOperation operation) {
        Set<String> held = requireOperation(actor, operation);
        requireGlobal(actor, operation.permission());
        return held;
    }

    /**
     * 对目标用户同时检查页面和动作的数据范围；任一不足即拒绝。 Checks page and action data scopes for the target user; denies
     * either failure.
     */
    default void requireOperationUser(String actor, UUID target, PlatformOperation operation) {
        requireOperation(actor, operation);
        requireUser(actor, target, operation.page().accessPermission());
        requireUser(actor, target, operation.permission());
    }
}
