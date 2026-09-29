package com.lumira.platform.common;

/** Request-layer rate-limit contract for upload-consuming feature modules. */
@FunctionalInterface
public interface UploadRateLimitPort {
    boolean allow(String actor, String source);
}
