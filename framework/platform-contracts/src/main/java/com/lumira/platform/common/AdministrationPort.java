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
    default Set<String> require(String actor, String permission) {
        return Set.of();
    }

    /** 要求操作者对目标用户具有指定权限及数据范围。 Requires permission and data scope for the target user. */
    default void requireUser(String actor, UUID target, String permission) {}

    /**
     * 要求指定权限具有全局范围，范围不足则拒绝。 Requires global scope for the permission; denies insufficient scope.
     */
    default void requireGlobal(String actor, String permission) {}

    /** 检查操作者是否具有该权限的全局范围。 Reports whether the actor holds global scope for the permission. */
    default boolean hasGlobal(String actor, String permission) {
        return false;
    }

    /**
     * 检查当前请求上下文中操作者是否具有该权限的全局范围。 Reports whether the actor holds global scope for the permission in
     * the request context.
     */
    default boolean hasRequestGlobal(String actor, String permission) {
        return hasGlobal(actor, permission);
    }

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
        // requireOperation 已强制校验 GLOBAL 声明；此处仅强化受限范围声明，避免重复校验增加数据库和边界锁开销。
        // requireOperation already enforces GLOBAL declarations. Only strengthen scoped
        // declarations here; repeating the same check adds database and boundary-lock work.
        if (operation.page().scope() != PlatformOperation.Scope.GLOBAL)
            requireGlobal(actor, operation.page().accessPermission());
        if (operation.scope() != PlatformOperation.Scope.GLOBAL)
            requireGlobal(actor, operation.permission());
        return held;
    }

    /**
     * 为经验证按记录隔离的写操作启用并发授权。实现可保留常规串行化，调用方仍须在同一事务内保护记录版本与不变量。宿主仅对具备数据库并发测试的操作启用该路径，且此后绝不将其升级为常规排他管理事务；复合操作须从一开始选择排他路径。
     * Opts a verified record-isolated writer into concurrent authorization. Implementations may
     * retain the ordinary serialization; callers must still protect record versions and invariants
     * inside the same transaction. The host only opts in operations with database concurrency
     * tests. Do not upgrade this transaction to ordinary exclusive administration afterward;
     * composite operations must select the exclusive path from the start.
     */
    default Set<String> requireConcurrentOperationGlobal(
            String actor, PlatformOperation operation) {
        return requireOperationGlobal(actor, operation);
    }

    /**
     * 动作要求全局权限，页面目标可见范围仍可受限。 A globally scoped action on a page whose own target visibility may be
     * scoped.
     */
    default Set<String> requireOperationActionGlobal(String actor, PlatformOperation operation) {
        Set<String> held = requireOperation(actor, operation);
        if (operation.scope() != PlatformOperation.Scope.GLOBAL)
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
