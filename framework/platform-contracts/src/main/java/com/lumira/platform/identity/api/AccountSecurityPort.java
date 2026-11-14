package com.lumira.platform.identity.api;

import java.util.UUID;

/**
 * 持久账户级认证状态；角色和全局要求在其他位置推导。 Durable account-level authentication state. Role and global
 * requirements are derived elsewhere.
 */
public interface AccountSecurityPort {
    /**
     * 校验账户密码，失败时拒绝；不得记录明文密码。 Validates the account password and rejects failure; never log
     * plaintext passwords.
     */
    void checkPassword(String username, String password);

    /**
     * 登记用户自愿启用的第二因子并返回要求版本。 Registers voluntary second-factor enrollment and returns its
     * requirement revision.
     */
    UUID requireSecondFactor(UUID userId, String provider);

    /**
     * 保存提供方、自愿启用及待恢复状态并返回要求版本。 Stores provider, voluntary-enrollment and recovery-pending state and
     * returns its revision.
     */
    UUID setSecondFactorSources(
            UUID userId, String provider, boolean selfEnabled, boolean recoveryPending);

    /** 将指定提供方的账户因子标记为待恢复。 Marks the account factor for the provider as recovery-pending. */
    void markRecoveryPending(UUID userId, String provider);

    /** 完成指定提供方的因子恢复状态转换。 Completes the recovery transition for the account factor provider. */
    void completeRecovery(UUID userId, String provider);

    /**
     * 清除指定提供方的账户因子状态；调用方先完成授权及策略检查。 Clears account factor state for the provider; callers first
     * authorize and check policy.
     */
    void clearSecondFactor(UUID userId, String provider);

    /**
     * 轮换并返回主凭据版本，使旧版本凭据事实失效。 Rotates and returns the primary credential revision, invalidating old
     * revision facts.
     */
    UUID bumpPrimaryCredentialRevision(UUID userId);

    /**
     * 轮换并返回因子凭据版本，使旧版本因子事实失效。 Rotates and returns the factor credential revision, invalidating old
     * revision facts.
     */
    UUID bumpFactorCredentialRevision(UUID userId);

    /**
     * 撤销用户会话，仅保留指定会话；无保留标识时全部撤销。 Revokes user sessions except the specified retained session; no
     * retained ID revokes all.
     */
    void revokeSessions(UUID userId, String retainedSessionId);
}
