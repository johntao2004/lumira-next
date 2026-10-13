package com.lumira.platform.identity;

import com.lumira.platform.common.FixedWindowBuckets;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Throttles current-password proofs on the authenticated change endpoint. Without it a stolen or
 * shared session could guess the password there without ever touching the login rate limiter.
 */
@Service
public class PasswordChangeLimiter {
    static final int ATTEMPTS_PER_MINUTE = 5;
    private final FixedWindowBuckets buckets;

    public PasswordChangeLimiter(FixedWindowBuckets buckets) {
        this.buckets = buckets;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allow(String actor) {
        return buckets.consume(FixedWindowBuckets.key("password-chg", actor), ATTEMPTS_PER_MINUTE);
    }

    /** Bound password attempts made before a fully authenticated session exists. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allowPendingSession(String sessionDigest) {
        if (sessionDigest == null || !sessionDigest.matches("[a-f0-9]{64}")) return false;
        return buckets.consume(
                FixedWindowBuckets.key("pwd-pending", sessionDigest), ATTEMPTS_PER_MINUTE);
    }
}
