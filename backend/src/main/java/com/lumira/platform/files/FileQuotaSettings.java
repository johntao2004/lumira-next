package com.lumira.platform.files;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Persisted system defaults used when an enabled role does not set a quota dimension. */
public record FileQuotaSettings(long maximumBytes, long maximumFiles) {
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public void validate() {
        if (!isSafeNonNegativeInteger(maximumBytes) || !isSafeNonNegativeInteger(maximumFiles)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidFileQuota");
        }
    }

    private static boolean isSafeNonNegativeInteger(long value) {
        return value >= 0 && value <= MAX_SAFE_INTEGER;
    }
}
