package com.lumira.platform.identity;

import com.lumira.platform.common.FixedWindowBuckets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Committed budgets bound challenge creation and failed verification across all instances. */
@Service
public class PasskeyRateLimiter {
    private final FixedWindowBuckets buckets;
    private final int issueGlobal;
    private final int issueSource;
    private final int issueSession;
    private final int verifyGlobal;
    private final int verifySource;
    private final int verifySession;

    public PasskeyRateLimiter(
            FixedWindowBuckets buckets,
            @Value("${lumira.passkey.issue-global-per-minute:1000}") int issueGlobal,
            @Value("${lumira.passkey.issue-source-per-minute:120}") int issueSource,
            @Value("${lumira.passkey.issue-session-per-minute:10}") int issueSession,
            @Value("${lumira.passkey.verify-global-per-minute:2000}") int verifyGlobal,
            @Value("${lumira.passkey.verify-source-per-minute:240}") int verifySource,
            @Value("${lumira.passkey.verify-session-per-minute:20}") int verifySession) {
        for (int limit :
                new int[] {
                    issueGlobal,
                    issueSource,
                    issueSession,
                    verifyGlobal,
                    verifySource,
                    verifySession
                }) {
            FixedWindowBuckets.validateLimit(limit);
        }
        this.buckets = buckets;
        this.issueGlobal = issueGlobal;
        this.issueSource = issueSource;
        this.issueSession = issueSession;
        this.verifyGlobal = verifyGlobal;
        this.verifySource = verifySource;
        this.verifySession = verifySession;
    }

    /**
     * Earlier scopes are consumed first so rotating source/session keys cannot evade the global
     * cap.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allow(String source, String sessionId, boolean verification) {
        String prefix = verification ? "pk-verify" : "pk-issue";
        if (!buckets.consume(
                FixedWindowBuckets.key(prefix, "global"),
                verification ? verifyGlobal : issueGlobal)) return false;
        if (!buckets.consume(
                FixedWindowBuckets.key(prefix + "-src", source),
                verification ? verifySource : issueSource)) return false;
        return sessionId == null
                || buckets.consume(
                        FixedWindowBuckets.key(prefix + "-sess", sessionId),
                        verification ? verifySession : issueSession);
    }
}
