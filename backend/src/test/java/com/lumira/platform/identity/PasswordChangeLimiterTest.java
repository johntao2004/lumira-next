package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumira.platform.common.FixedWindowBuckets;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PasswordChangeLimiterTest {
    @Test
    void stopsCurrentPasswordGuessingPerAccount() {
        var counts = new HashMap<String, Integer>();
        var buckets = Mockito.mock(FixedWindowBuckets.class);
        Mockito.when(buckets.consume(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(
                        call -> {
                            int used = counts.merge(call.getArgument(0), 1, Integer::sum);
                            return used <= (int) call.getArgument(1);
                        });
        var limiter = new PasswordChangeLimiter(buckets);
        for (int i = 0; i < PasswordChangeLimiter.ATTEMPTS_PER_MINUTE; i++)
            assertTrue(limiter.allow("alice"));
        assertFalse(limiter.allow("alice"));
        assertTrue(limiter.allow("bob"));
    }
}
