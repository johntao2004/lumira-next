package com.lumira.platform.identity;

import com.lumira.platform.common.FixedWindowBuckets;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Independent committed source/contact/session limits for all public challenge sends. */
@Service
public class PublicAuthRateLimiter {
    private final JdbcClient jdbc;
    private final FixedWindowBuckets buckets;
    private final SecurityPolicyPort security;

    public PublicAuthRateLimiter(
            JdbcClient jdbc, FixedWindowBuckets buckets, SecurityPolicyPort security) {
        this.jdbc = jdbc;
        this.buckets = buckets;
        this.security = security;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allow(
            PublicAuthPurpose purpose,
            String channel,
            String contactDigest,
            String sessionDigest,
            String sourceDigest) {
        var policy = security.currentPolicy();
        int accountLimit;
        int sourceLimit;
        String prefix;
        switch (purpose) {
            case REGISTRATION -> {
                accountLimit = policy.verificationRequestsPerMinute();
                sourceLimit = policy.verificationRequestsPerMinute();
                prefix = "reg";
            }
            case LOGIN -> {
                accountLimit = policy.loginAccountPerMinute();
                sourceLimit = policy.loginSourcePerMinute();
                prefix = "login";
            }
            case SMS_RECOVERY -> {
                accountLimit = policy.recoveryAccountPerMinute();
                sourceLimit = policy.recoverySourcePerMinute();
                prefix = "smsrec";
            }
            case REAUTHENTICATION -> {
                accountLimit = 10;
                sourceLimit = 20;
                prefix = "reauth";
            }
            default -> throw new IllegalStateException("Unknown public authentication purpose");
        }
        if (!buckets.consume(FixedWindowBuckets.key(prefix + "-source", sourceDigest), sourceLimit))
            return false;
        if (!buckets.consume(
                FixedWindowBuckets.key(prefix + "-session", sessionDigest), accountLimit))
            return false;
        if (!buckets.consume(
                FixedWindowBuckets.key(prefix + "-contact", contactDigest), accountLimit))
            return false;
        // Reauthentication codes use their own V36 table and cannot be stored in the
        // public-auth rate table, whose purpose constraint intentionally excludes them.
        if (purpose == PublicAuthPurpose.REAUTHENTICATION) return true;
        int cooldown = policy.verificationCodeCooldownSeconds();
        int daily = policy.verificationDailyLimit();
        return jdbc.sql(
                        "INSERT INTO platform_public_auth_rate(purpose,channel,rate_digest,requested_at,request_day,request_count) VALUES(:purpose,:channel,:digest,now(),CURRENT_DATE,1) ON CONFLICT(purpose,channel,rate_digest) DO UPDATE SET requested_at=now(),request_day=CASE WHEN platform_public_auth_rate.request_day=CURRENT_DATE THEN platform_public_auth_rate.request_day ELSE CURRENT_DATE END,request_count=CASE WHEN platform_public_auth_rate.request_day=CURRENT_DATE THEN platform_public_auth_rate.request_count+1 ELSE 1 END WHERE (platform_public_auth_rate.requested_at IS NULL OR platform_public_auth_rate.requested_at<=now()-(:cooldown * interval '1 second')) AND (platform_public_auth_rate.request_day IS NULL OR platform_public_auth_rate.request_day<>CURRENT_DATE OR platform_public_auth_rate.request_count<:daily) RETURNING 1")
                .param("purpose", purpose.name())
                .param("channel", channel)
                .param("digest", contactDigest)
                .param("cooldown", cooldown)
                .param("daily", daily)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** Separate short CPU-work limits; completion must not reuse the send cooldown bucket. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allowRegistrationCompletion(
            String contactDigest, String sessionDigest, String sourceDigest) {
        int limit = security.currentPolicy().verificationRequestsPerMinute();
        return buckets.consume(FixedWindowBuckets.key("regcomp-source", sourceDigest), limit)
                && buckets.consume(FixedWindowBuckets.key("regcomp-session", sessionDigest), limit)
                && buckets.consume(FixedWindowBuckets.key("regcomp-contact", contactDigest), limit);
    }

    /** SMS password-reset hashing has its own committed source/session/contact work budget. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allowSmsRecoveryCompletion(
            String contactDigest, String sessionDigest, String sourceDigest) {
        var policy = security.currentPolicy();
        return buckets.consume(
                        FixedWindowBuckets.key("smscomp-source", sourceDigest),
                        policy.recoverySourcePerMinute())
                && buckets.consume(
                        FixedWindowBuckets.key("smscomp-session", sessionDigest),
                        policy.recoveryAccountPerMinute())
                && buckets.consume(
                        FixedWindowBuckets.key("smscomp-contact", contactDigest),
                        policy.recoveryAccountPerMinute());
    }

    /**
     * Committed verification limits are consumed before opening the challenge-write transaction. A
     * challenge bucket bounds guessing, while source and session buckets prevent identifier
     * rotation from bypassing the purpose limit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allowVerification(
            PublicAuthPurpose purpose,
            String sessionDigest,
            String sourceDigest,
            String challengeId) {
        var policy = security.currentPolicy();
        String prefix;
        int sourceLimit;
        int sessionLimit;
        switch (purpose) {
            case REGISTRATION -> {
                prefix = "regv";
                sourceLimit = policy.verificationRequestsPerMinute();
                sessionLimit = policy.verificationRequestsPerMinute();
            }
            case LOGIN -> {
                prefix = "logv";
                sourceLimit = policy.loginSourcePerMinute();
                sessionLimit = policy.loginAccountPerMinute();
            }
            case SMS_RECOVERY -> {
                prefix = "smsv";
                sourceLimit = policy.recoverySourcePerMinute();
                sessionLimit = policy.recoveryAccountPerMinute();
            }
            case REAUTHENTICATION -> {
                prefix = "reauthv";
                sourceLimit = 20;
                sessionLimit = 10;
            }
            default -> throw new IllegalStateException("Unknown public authentication purpose");
        }
        ;
        return buckets.consume(
                        FixedWindowBuckets.key(prefix + "-source", sourceDigest), sourceLimit)
                && buckets.consume(
                        FixedWindowBuckets.key(prefix + "-session", sessionDigest), sessionLimit)
                && buckets.consume(
                        FixedWindowBuckets.key(
                                prefix + "-challenge", purpose.name() + ":" + challengeId),
                        policy.verificationFailureLimit());
    }
}
