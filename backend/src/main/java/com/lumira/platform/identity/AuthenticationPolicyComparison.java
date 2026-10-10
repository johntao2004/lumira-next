package com.lumira.platform.identity;

import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;

/** Shared comparison for proofs that rotate only the account's MFA factor epoch. */
final class AuthenticationPolicyComparison {
    private AuthenticationPolicyComparison() {}

    static boolean sameExceptFactorEpoch(
            EffectiveAuthenticationPolicyPort.EffectivePolicy before,
            EffectiveAuthenticationPolicyPort.EffectivePolicy after) {
        return before.primaryCredentialRevision().equals(after.primaryCredentialRevision())
                && before.allowedMethods().equals(after.allowedMethods())
                && before.usableMethods().equals(after.usableMethods())
                && before.mfaRequired() == after.mfaRequired()
                && before.requirementSources().equals(after.requirementSources())
                && before.captchaRequired() == after.captchaRequired();
    }
}
