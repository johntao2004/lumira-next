package com.lumira.platform.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Invoked only inside a plugin lease after primary authentication. */
public interface SecondFactorProvider {
    String id();

    boolean verify(UUID userId, String code);

    /** Returns the verified evidence label; null means the supplied factor was invalid. */
    default String verifyEvidence(UUID userId, String code) {
        return verify(userId, code) ? "TOTP" : null;
    }

    /** A retained session may be refreshed after a one-use recovery code rotates factor state. */
    default String verifyEvidence(UUID userId, String code, String retainedSessionId) {
        return verifyEvidence(userId, code);
    }

    /** Send a restricted recovery ticket only after the host has validated the pending login. */
    default RecoveryRequest requestRecovery(UUID userId) {
        throw new UnsupportedOperationException("mfaRecoveryUnavailable");
    }

    /** False when the installed provider cannot securely serve this request on this instance. */
    default boolean available() {
        return true;
    }

    /**
     * True only when this provider implements the complete restricted enrollment and recovery
     * ticket lifecycle required by policy-enforced MFA. Existing verification-only providers stay
     * usable for already-configured factors but cannot be selected for new forced MFA policies.
     */
    default boolean supportsRestrictedEnrollmentAndRecovery() {
        return false;
    }

    /** Provider-owned status is nullable when account state cannot be safely established. */
    default AccountStatus status(UUID userId) {
        return null;
    }

    /** Create a short-lived setup bound to the supplied persistent challenge and session digest. */
    default EnrollmentSetup startEnrollment(
            UUID userId, String username, String label, UUID challengeId, String sessionHash) {
        throw new UnsupportedOperationException("secondFactorEnrollmentUnavailable");
    }

    /**
     * Confirm a setup once; selfEnabled distinguishes voluntary setup from policy-required setup.
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
