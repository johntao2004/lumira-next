package com.lumira.platform.modules;

/**
 * 供 HTTP 适配器和后台任务查询启用状态的只读契约。 Read-only lifecycle contract for HTTP adapters and background jobs.
 */
@FunctionalInterface
public interface ModuleRuntime {
    /** 查询模块当前是否启用，不授予业务权限。 Returns whether the module is enabled; grants no business permission. */
    boolean isEnabled(String moduleId);
}
