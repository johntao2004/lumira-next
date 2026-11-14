package com.lumira.platform.common;

import java.util.UUID;

/**
 * 向模块提供审计写入契约，不暴露查询实现。 Write-side audit contract exposed to modules without leaking the audit query
 * implementation.
 */
public interface AuditPort {
    /**
     * 记录操作者、动作及目标的审计，不代替权限检查。 Records actor, action and target for audit; does not replace
     * authorization.
     */
    void append(String actor, String action, UUID targetId);

    /**
     * 使用调用方指定 ID 关联审计记录；默认方法兼容旧插件，宿主实现持久化该 ID。 Append an audit operation under a caller-selected ID
     * so another durable record can point to that exact operation. Existing plugin implementations
     * remain binary compatible; the host audit repository overrides this method and persists the
     * supplied ID.
     */
    default void append(UUID id, String actor, String action, UUID targetId) {
        append(actor, action, targetId);
    }
}
