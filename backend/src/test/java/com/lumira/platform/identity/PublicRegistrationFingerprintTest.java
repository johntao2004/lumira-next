package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicRegistrationFingerprintTest {
    @Test
    void captchaProofRotationDoesNotChangeBusinessRequestFingerprint() {
        var first = completion("captcha-one", "Ada");
        var retried = completion("captcha-two", "Ada");

        assertArrayEquals(
                PublicRegistrationService.completionBytes(first, Map.of("department", "Research")),
                PublicRegistrationService.completionBytes(
                        retried, Map.of("department", "Research")));
        assertFalse(
                java.util.Arrays.equals(
                        PublicRegistrationService.completionBytes(
                                first, Map.of("department", "Research")),
                        PublicRegistrationService.completionBytes(
                                completion("captcha-two", "Grace"),
                                Map.of("department", "Research"))));
    }

    private static PublicAuthRequests.RegistrationCompletion completion(
            String captchaProofId, String displayName) {
        var input = new PublicAuthRequests.RegistrationCompletion();
        input.proofId = "registration-proof";
        input.username = "ada-lovelace";
        input.displayName = displayName;
        input.password = "new-password";
        input.policyVersion = 3L;
        input.agreementVersion = 4L;
        input.acceptAgreements = true;
        input.requestId = "request-123";
        input.captchaProofId = captchaProofId;
        input.profileSchemaVersion = 5L;
        input.wechatRegistrationProofId = "wechat-proof";
        return input;
    }
}
