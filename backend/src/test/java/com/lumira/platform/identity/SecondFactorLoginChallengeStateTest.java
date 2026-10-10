package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SecondFactorLoginChallengeStateTest {
    @Test
    void recoveryPendingTakesPrecedenceOverConfiguredState() {
        assertEquals(
                "RECOVERY_REQUIRED", SecondFactorLoginService.challengeState(true, false, true));
    }

    @Test
    void selfEnabledFactorWithoutConfigurationRequiresRecovery() {
        assertEquals(
                "RECOVERY_REQUIRED", SecondFactorLoginService.challengeState(false, true, false));
    }

    @Test
    void configuredFactorRequiresMfa() {
        assertEquals("MFA_REQUIRED", SecondFactorLoginService.challengeState(false, false, true));
    }

    @Test
    void unconfiguredOptionalFactorRequiresEnrollment() {
        assertEquals(
                "ENROLLMENT_REQUIRED",
                SecondFactorLoginService.challengeState(false, false, false));
    }
}
