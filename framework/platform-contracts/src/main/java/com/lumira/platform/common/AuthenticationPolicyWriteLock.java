package com.lumira.platform.common;

/**
 * 认证敏感变更前获取宿主持有的认证策略行锁。 Acquires the host-owned authentication-policy row lock before
 * auth-sensitive mutations.
 */
public interface AuthenticationPolicyWriteLock {
    /**
     * 在认证相关事务内取得策略读取保护，防止并发策略变更。 Acquires policy protection within authentication transactions
     * against concurrent policy changes.
     */
    void lockForAuthentication();

    /**
     * 在策略更新事务内取得排他保护，维持与认证路径的锁顺序。 Acquires exclusive protection within policy updates, preserving
     * authentication lock order.
     */
    void lockForUpdate();
}
