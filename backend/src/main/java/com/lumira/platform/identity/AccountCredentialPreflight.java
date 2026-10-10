package com.lumira.platform.identity;

import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.AuthorizationContextBoundaryPort;
import com.lumira.platform.common.RateLimitExceededException;
import com.lumira.platform.identity.api.PasswordContract;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

/** Shared lock-order and password preflight rules for account credential updates. */
final class AccountCredentialPreflight {
    private AccountCredentialPreflight() {}

    static void lockAuthenticationWriteBoundary(
            AuthenticationPolicyWriteLock policyLock,
            AuthorizationContextBoundaryPort authorizationBoundary) {
        if (policyLock != null) policyLock.lockForAuthentication();
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
    }

    static String passwordHash(JdbcClient jdbc, UUID accountId) {
        return jdbc.sql("SELECT password_hash FROM platform_user WHERE id=:id")
                .param("id", accountId)
                .query(String.class)
                .single();
    }

    static void requireCurrentPassword(PasswordEncoder passwords, String password, String hash) {
        if (!PasswordHashCapacity.tryAcquire())
            throw new RateLimitExceededException("passwordHashCapacity");
        try {
            if (!PasswordContract.compatibleWithStoredHash(password, hash)
                    || !passwords.matches(password, hash)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "wrongCurrentPassword");
            }
        } finally {
            PasswordHashCapacity.release();
        }
    }
}
