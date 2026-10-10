package com.lumira.platform.identity;

import com.lumira.platform.common.FixedWindowBuckets;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PhoneVerificationLimiter {
    private final FixedWindowBuckets buckets;
    private final SecurityPolicyPort security;

    @Autowired
    public PhoneVerificationLimiter(FixedWindowBuckets buckets, SecurityPolicyPort security) {
        this.buckets = buckets;
        this.security = security;
    }

    public PhoneVerificationLimiter(FixedWindowBuckets buckets) {
        this(buckets, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allow(String actor) {
        return buckets.consume(
                FixedWindowBuckets.key("phone-verify", actor),
                security == null ? 10 : security.currentPolicy().verificationRequestsPerMinute());
    }
}
