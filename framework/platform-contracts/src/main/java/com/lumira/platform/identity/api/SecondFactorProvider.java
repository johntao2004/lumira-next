package com.lumira.platform.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 仅在主认证完成后、插件租约内调用。 Invoked only inside a plugin lease after primary authentication. */
public interface SecondFactorProvider {
    /** 返回第二因子提供方的稳定标识。 Returns the stable second-factor provider identifier. */
    String id();

    /**
     * 校验用户提交的因子，返回是否有效；调用须处于插件租约内。 Verifies the user factor and returns validity; calls require a
     * plugin lease.
     */
    boolean verify(UUID userId, String code);

    /**
     * 返回已验证的凭据标签；null 表示因子无效。 Returns the verified evidence label; null means the supplied factor
     * was invalid.
     */
    default String verifyEvidence(UUID userId, String code) {
        return verify(userId, code) ? "TOTP" : null;
    }

    /**
     * 一次性恢复码轮换因子状态后，可刷新保留的会话。 A retained session may be refreshed after a one-use recovery code
     * rotates factor state.
     */
    default String verifyEvidence(UUID userId, String code, String retainedSessionId) {
        return verifyEvidence(userId, code);
    }

    /**
     * 宿主验证待完成登录后，才发送受限恢复票据。 Send a restricted recovery ticket only after the host has validated the
     * pending login.
     */
    default RecoveryRequest requestRecovery(UUID userId) {
        throw new UnsupportedOperationException("mfaRecoveryUnavailable");
    }

    /**
     * 当前实例无法安全处理请求时返回 false。 False when the installed provider cannot securely serve this request
     * on this instance.
     */
    default boolean available() {
        return true;
    }

    /**
     * 仅完整实现强制 MFA 所需受限注册及恢复流程才返回 true；旧验证型插件仅支持已有因子，不得用于新强制策略。 True only when this provider
     * implements the complete restricted enrollment and recovery ticket lifecycle required by
     * policy-enforced MFA. Existing verification-only providers stay usable for already-configured
     * factors but cannot be selected for new forced MFA policies.
     */
    default boolean supportsRestrictedEnrollmentAndRecovery() {
        return false;
    }

    /**
     * 无法安全确定账户状态时，提供方状态可为 null。 Provider-owned status is nullable when account state cannot be
     * safely established.
     */
    default AccountStatus status(UUID userId) {
        return null;
    }

    /**
     * 创建绑定持久挑战及会话摘要的短期设置流程。 Create a short-lived setup bound to the supplied persistent challenge
     * and session digest.
     */
    default EnrollmentSetup startEnrollment(
            UUID userId, String username, String label, UUID challengeId, String sessionHash) {
        throw new UnsupportedOperationException("secondFactorEnrollmentUnavailable");
    }

    /**
     * 设置仅确认一次；selfEnabled 区分自愿启用与策略强制。 Confirm a setup once; selfEnabled distinguishes voluntary
     * setup from policy-required setup.
     */
    default EnrollmentConfirmation confirmEnrollment(
            UUID userId, UUID challengeId, String sessionHash, String code, boolean selfEnabled) {
        throw new UnsupportedOperationException("secondFactorEnrollmentUnavailable");
    }

    record AccountStatus(boolean configured, int recoveryCodesRemaining) {}

    record EnrollmentSetup(UUID challengeId, String secret, String uri, Instant expiresAt) {}

    record EnrollmentConfirmation(List<String> recoveryCodes) {
        public EnrollmentConfirmation {
            recoveryCodes = List.copyOf(recoveryCodes);
        }
    }

    record RecoveryRequest(Instant expiresAt) {}
}
