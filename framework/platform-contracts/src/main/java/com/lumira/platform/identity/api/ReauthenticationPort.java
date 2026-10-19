package com.lumira.platform.identity.api;

import java.util.UUID;

/**
 * 在受保护写入的同一事务中消费短期、绑定动作的证明。 Consumes a short-lived, action-bound proof in the same transaction as
 * the protected write.
 */
public interface ReauthenticationPort {
    enum Action {
        MFA_ENROLL,
        MFA_RENAME,
        MFA_RECOVERY_REGENERATE,
        MFA_DISABLE,
        MFA_ADMIN_RESET,
        MFA_REPLACE,
        WECHAT_BIND,
        WECHAT_UNBIND
    }

    /**
     * 在受保护写入事务内一次性消费绑定用户、动作及会话的票据。 Consumes the user/action/session-bound ticket once within the
     * protected write transaction.
     */
    void consume(UUID ticketId, UUID userId, Action action, String sessionId);
}
