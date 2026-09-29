package com.lumira.platform.identity;

import com.lumira.platform.common.FixedWindowBuckets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailVerificationLimiterTest {
    @Test void usesValidAccountSpecificBucketAndPropagatesDenial() {
        var buckets=mock(FixedWindowBuckets.class);
        var limiter=new EmailVerificationLimiter(buckets);
        var key=FixedWindowBuckets.key("email-verify","account-a");
        when(buckets.consume(key,10)).thenReturn(true,false);
        assertTrue(limiter.allow("account-a"));
        assertFalse(limiter.allow("account-a"));
        assertFalse(limiter.allow("account-b"));
        verify(buckets,times(2)).consume(key,10);
        verify(buckets).consume(FixedWindowBuckets.key("email-verify","account-b"),10);
    }
}
