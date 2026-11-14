package com.lumira.platform.common;

import java.security.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

/** Atomic PostgreSQL minute buckets shared across application instances. */
@Repository
public class FixedWindowBuckets {
    private final JdbcClient jdbc;

    public FixedWindowBuckets(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static void validateLimit(int limit) {
        if (limit < 1 || limit > 10000)
            throw new IllegalArgumentException("Rate limit must be between 1 and 10000");
    }

    public static String key(String namespace, String value) {
        return RateLimitKeys.key(namespace, value);
    }

    public boolean consume(String key, int limit) {
        validateLimit(limit);
        int attempts =
                jdbc.sql(
                                "INSERT INTO platform_rate_bucket(bucket_key,window_start,attempts) VALUES(:key,date_trunc('minute',CURRENT_TIMESTAMP),1) ON CONFLICT(bucket_key) DO UPDATE SET window_start=date_trunc('minute',CURRENT_TIMESTAMP),attempts=CASE WHEN platform_rate_bucket.window_start<date_trunc('minute',CURRENT_TIMESTAMP) THEN 1 ELSE LEAST(platform_rate_bucket.attempts+1,:cap) END RETURNING attempts")
                        .param("key", key)
                        .param("cap", limit + 1)
                        .query(Integer.class)
                        .single();
        return attempts <= limit;
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void cleanup() {
        jdbc.sql(
                        "DELETE FROM platform_rate_bucket WHERE window_start<CURRENT_TIMESTAMP-INTERVAL '1 day'")
                .update();
    }
}
