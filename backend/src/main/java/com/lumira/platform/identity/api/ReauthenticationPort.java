package com.lumira.platform.identity.api;

import java.util.UUID;

/** Consumes a short-lived, action-bound proof in the same transaction as the protected write. */
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

    void consume(UUID ticketId, UUID userId, Action action, String sessionId);
}
