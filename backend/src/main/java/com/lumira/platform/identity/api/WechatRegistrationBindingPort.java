package com.lumira.platform.identity.api;

import java.util.UUID;

/** Optional, purpose-bound WeChat registration binding performed in the account transaction. */
public interface WechatRegistrationBindingPort {
    BindingResult consumeForRegistration(
            String wechatRegistrationProofId,
            String registrationContactProofId,
            UUID newAccountId,
            String sessionDigest);

    enum Status {
        NOT_REQUESTED,
        BOUND,
        FAILED
    }

    record BindingResult(Status status, String reason) {
        public BindingResult {
            if (status == null || reason != null && reason.length() > 64)
                throw new IllegalArgumentException("invalidWechatRegistrationResult");
            if (status == Status.FAILED && (reason == null || reason.isBlank()))
                throw new IllegalArgumentException("invalidWechatRegistrationResult");
            if (status != Status.FAILED && reason != null)
                throw new IllegalArgumentException("invalidWechatRegistrationResult");
        }

        public static BindingResult notRequested() {
            return new BindingResult(Status.NOT_REQUESTED, null);
        }

        public static BindingResult bound() {
            return new BindingResult(Status.BOUND, null);
        }

        public static BindingResult failed(String reason) {
            return new BindingResult(Status.FAILED, reason);
        }
    }
}
