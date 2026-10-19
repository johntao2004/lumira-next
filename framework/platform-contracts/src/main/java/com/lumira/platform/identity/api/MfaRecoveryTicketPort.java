package com.lumira.platform.identity.api;

import java.time.Instant;
import java.util.UUID;

/**
 * 宿主持有、与验证器插件共用的一次性恢复票据。 Host-owned, one-use recovery tickets shared with the authenticator plugin.
 */
public interface MfaRecoveryTicketPort {
    record Issued(String value, Instant expiresAt) {}

    enum ConsumeResult {
        CONSUMED,
        INVALID,
        LOCKED
    }

    /**
     * 建立 RECOVERY_PENDING 后，在凭据变更事务中签发。 Issue inside the credential-change transaction after
     * RECOVERY_PENDING is established.
     */
    Issued issue(UUID userId);

    /**
     * 仅同一账户及因子版本仍待恢复时允许消费。 Consume only while the same account/factor revision remains
     * recovery-pending.
     */
    ConsumeResult consume(UUID userId, String value);

    /** 撤销该用户尚存的恢复票据。 Revokes outstanding recovery tickets for the user. */
    void revoke(UUID userId);
}
