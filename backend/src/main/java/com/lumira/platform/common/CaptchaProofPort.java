package com.lumira.platform.common;

import java.util.Map;
import java.util.UUID;

/**
 * Narrow action-bound CAPTCHA contract. Implementations own challenge/provider details; callers
 * resolve the current requirement immediately before acting and consume proofs in the caller's
 * transaction.
 */
public interface CaptchaProofPort {
    Requirement requirement(Action action, UUID accountId, UUID defaultRoleId);

    /**
     * Resolve risk-sensitive requirements from the current request and persisted facts. Providers
     * that do not use request context may keep the three-argument implementation.
     */
    default Requirement requirement(
            Action action, UUID accountId, UUID defaultRoleId, RequestFacts requestFacts) {
        return requirement(action, accountId, defaultRoleId);
    }

    /** Whether the real primary and accessible-alternative providers are ready for required use. */
    default boolean readyForRequiredPolicy() {
        return false;
    }

    /**
     * Whether an action can be completed under its current non-request-specific policy. Risk-based
     * actions may still become required for an individual request; callers must use
     * requirement(..., request) immediately before that operation.
     */
    default boolean readyFor(Action action, UUID accountId, UUID defaultRoleId) {
        Requirement current = requirement(action, accountId, defaultRoleId);
        return !current.required() || readyForRequiredPolicy();
    }

    /**
     * Non-consuming early validation for callers that must perform expensive work before commit.
     */
    default void preflight(
            String proofId,
            Action action,
            String policyRevision,
            RequestFacts requestFacts,
            String expectedAttemptId) {
        // Final consume remains authoritative; providers may override to reject bad proofs early.
    }

    default void consume(
            String proofId, Action action, String policyRevision, RequestFacts requestFacts) {
        consume(proofId, action, policyRevision, requestFacts, null);
    }

    void consume(
            String proofId,
            Action action,
            String policyRevision,
            RequestFacts requestFacts,
            String expectedAttemptId);

    enum Action {
        PASSWORD_LOGIN,
        PASSKEY_LOGIN,
        WECHAT_LOGIN,
        LOGIN_CODE_SEND,
        LOGIN_CODE_VERIFY,
        REGISTRATION_SEND,
        REGISTRATION_VERIFY,
        REGISTRATION_COMPLETE,
        PASSWORD_RECOVERY_REQUEST,
        PASSWORD_RECOVERY_VERIFY,
        PASSWORD_RECOVERY_CONFIRM,
        POST_PRIMARY_AUTH
    }

    record Requirement(boolean required, String policyRevision, String type) {
        public Requirement {
            if (policyRevision == null
                    || policyRevision.isBlank()
                    || required && (type == null || type.isBlank()))
                throw new IllegalArgumentException("invalidCaptchaRequirement");
        }
    }

    /**
     * Framework-free, privacy-preserving facts derived by the HTTP adapter. Session binding is a
     * 64-character digest; risk maps carry only opaque persisted bucket keys, never raw addresses
     * or session identifiers.
     */
    record RequestFacts(
            String sessionDigest,
            Map<String, String> sourceRateBucketKeys,
            Map<String, String> sessionRateBucketKeys) {
        public RequestFacts {
            if (sessionDigest != null && !sessionDigest.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("invalidCaptchaSessionDigest");
            sourceRateBucketKeys = immutableBucketKeys(sourceRateBucketKeys);
            sessionRateBucketKeys = immutableBucketKeys(sessionRateBucketKeys);
        }

        public static RequestFacts none() {
            return new RequestFacts(null, Map.of(), Map.of());
        }

        private static Map<String, String> immutableBucketKeys(Map<String, String> keys) {
            if (keys == null || keys.isEmpty()) return Map.of();
            var copy = Map.copyOf(keys);
            copy.forEach(
                    (namespace, key) -> {
                        if (namespace == null
                                || !namespace.matches("[a-z-]{1,15}")
                                || key == null
                                || !key.matches(namespace + ":[a-f0-9]{64}"))
                            throw new IllegalArgumentException("invalidCaptchaRiskFacts");
                    });
            return copy;
        }
    }
}
