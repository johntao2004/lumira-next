package com.lumira.platform.common;

/**
 * 表达限流拒绝及重试等待，不负责执行限流。 Represents a rate-limit rejection and retry delay; it does not enforce
 * limits.
 */
public final class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String code) {
        super(code);
    }
}
