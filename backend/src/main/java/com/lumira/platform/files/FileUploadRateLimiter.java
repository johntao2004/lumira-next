package com.lumira.platform.files;

import com.lumira.platform.common.FixedWindowBuckets;
import com.lumira.platform.common.UploadRateLimitPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileUploadRateLimiter implements UploadRateLimitPort {
    private final FixedWindowBuckets buckets;
    private final int accountLimit, sourceLimit;

    public FileUploadRateLimiter(
            FixedWindowBuckets buckets,
            @Value("${lumira.files.account-per-minute:30}") int accountLimit,
            @Value("${lumira.files.source-per-minute:120}") int sourceLimit) {
        FixedWindowBuckets.validateLimit(accountLimit);
        FixedWindowBuckets.validateLimit(sourceLimit);
        this.buckets = buckets;
        this.accountLimit = accountLimit;
        this.sourceLimit = sourceLimit;
    }

    // Return denial rather than throw so consumed counters commit before the HTTP rejection.
    @Override
    @Transactional
    public boolean allow(String actor, String source) {
        if (!buckets.consume(FixedWindowBuckets.key("upload-source", source), sourceLimit))
            return false;
        return buckets.consume(FixedWindowBuckets.key("upload-account", actor), accountLimit);
    }
}
