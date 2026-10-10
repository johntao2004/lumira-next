package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.FixedWindowBuckets;
import org.junit.jupiter.api.Test;

class FileUploadRateLimiterTest {
    @Test
    void deniedSourceDoesNotCreateAccountBucket() {
        var buckets = mock(FixedWindowBuckets.class);
        var limiter = new FileUploadRateLimiter(buckets, 30, 120);
        assertFalse(limiter.allow("actor", "source"));
        verify(buckets).consume(FixedWindowBuckets.key("upload-source", "source"), 120);
        verifyNoMoreInteractions(buckets);
    }

    @Test
    void allowedSourceStillRequiresAccountCapacity() {
        var buckets = mock(FixedWindowBuckets.class);
        when(buckets.consume(FixedWindowBuckets.key("upload-source", "source"), 120))
                .thenReturn(true);
        assertFalse(new FileUploadRateLimiter(buckets, 30, 120).allow("actor", "source"));
        verify(buckets).consume(FixedWindowBuckets.key("upload-account", "actor"), 30);
    }

    @Test
    void namespacesSeparateAndFitThePersistedKey() {
        assertNotEquals(
                FixedWindowBuckets.key("account", "actor"),
                FixedWindowBuckets.key("upload-account", "actor"));
        assertEquals(79, FixedWindowBuckets.key("upload-account", "actor").length());
        assertThrows(
                IllegalArgumentException.class,
                () -> FixedWindowBuckets.key("namespace-too-long", "actor"));
        assertThrows(IllegalArgumentException.class, () -> new FileUploadRateLimiter(null, 0, 120));
    }
}
