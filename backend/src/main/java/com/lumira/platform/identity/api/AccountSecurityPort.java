package com.lumira.platform.identity.api;

import java.util.UUID;

/**
 * Durable account-level authentication state. Role and global requirements are derived elsewhere.
 */
public interface AccountSecurityPort {
    void checkPassword(String username, String password);

    UUID requireSecondFactor(UUID userId, String provider);

    UUID setSecondFactorSources(
            UUID userId, String provider, boolean selfEnabled, boolean recoveryPending);

    void markRecoveryPending(UUID userId, String provider);

    void completeRecovery(UUID userId, String provider);

    void clearSecondFactor(UUID userId, String provider);

    UUID bumpPrimaryCredentialRevision(UUID userId);

    UUID bumpFactorCredentialRevision(UUID userId);

    void revokeSessions(UUID userId, String retainedSessionId);
}
