package com.lumira.platform.identity;
import com.lumira.platform.common.FixedWindowBuckets;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
@Service
public class EmailVerificationLimiter {
    private final FixedWindowBuckets buckets; private final SecurityPolicyPort security;
    @Autowired public EmailVerificationLimiter(FixedWindowBuckets buckets,SecurityPolicyPort security){this.buckets=buckets;this.security=security;}
    public EmailVerificationLimiter(FixedWindowBuckets buckets){this(buckets,null);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean allow(String actor){return buckets.consume(FixedWindowBuckets.key("email-verify",actor),security==null?10:security.currentPolicy().verificationRequestsPerMinute());}
}
