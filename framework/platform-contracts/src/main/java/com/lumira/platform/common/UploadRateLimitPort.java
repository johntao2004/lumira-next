package com.lumira.platform.common;

/** 供接收上传内容的模块调用的请求层限流契约。 Request-layer rate-limit contract for upload-consuming feature modules. */
@FunctionalInterface
public interface UploadRateLimitPort {
    /**
     * 按操作者与来源检查上传额度，false 表示拒绝。 Checks upload allowance for actor and source; false denies the
     * request.
     */
    boolean allow(String actor, String source);
}
