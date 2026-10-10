package com.lumira.platform.identity;

import com.lumira.platform.common.FixedWindowBuckets;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginRateLimiter {
    private final FixedWindowBuckets buckets;
    private final int accountLimit, sourceLimit;
    private final SecurityPolicyPort security;

    @Autowired
    public LoginRateLimiter(FixedWindowBuckets buckets, SecurityPolicyPort security) {
        this(buckets, 30, 120, security);
    }

    public LoginRateLimiter(
            FixedWindowBuckets buckets,
            @Value("${lumira.login.account-per-minute:30}") int accountLimit,
            @Value("${lumira.login.source-per-minute:120}") int sourceLimit) {
        this(buckets, accountLimit, sourceLimit, null);
    }

    private LoginRateLimiter(
            FixedWindowBuckets buckets,
            int accountLimit,
            int sourceLimit,
            SecurityPolicyPort security) {
        FixedWindowBuckets.validateLimit(accountLimit);
        FixedWindowBuckets.validateLimit(sourceLimit);
        this.buckets = buckets;
        this.accountLimit = accountLimit;
        this.sourceLimit = sourceLimit;
        this.security = security;
    }

    static String key(String kind, String value) {
        return FixedWindowBuckets.key(kind, value);
    }

    @Transactional
    public boolean allow(String account, String source) {
        var policy = security == null ? null : security.currentPolicy();
        int sourceCap = policy == null ? sourceLimit : policy.loginSourcePerMinute();
        int accountCap = policy == null ? accountLimit : policy.loginAccountPerMinute();
        if (!buckets.consume(key("source", source), sourceCap)) return false;
        return buckets.consume(key("account", account), accountCap);
    }
}
