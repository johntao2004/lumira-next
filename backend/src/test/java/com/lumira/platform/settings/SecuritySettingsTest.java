package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SecuritySettingsTest {
    @Test
    void defaultsMatchExistingRuntimeAndAcceptUnicodePasswords() {
        var defaults = SecuritySettings.defaults();
        defaults.validate();
        assertTrue(defaults.passwordValid("long-enough-password"));
        assertTrue(defaults.passwordValid("足够长的安全密码abcd"));
        assertFalse(defaults.passwordValid("short"));
        assertTrue(defaults.passwordValid("x".repeat(73)));
        assertTrue(defaults.passwordValid("x".repeat(256)));
        assertFalse(defaults.passwordValid("x".repeat(257)));
    }

    @Test
    void requirementsAreAppliedWithoutAllowingInvalidBounds() {
        var policy =
                new SecuritySettings(12, true, true, true, false, 2, 4, 2, 4, 600, 60, 3, 5, 3);
        policy.validate();
        assertFalse(policy.passwordValid("onlylowercasepassword"));
        assertFalse(policy.passwordValid("Onlylowercasepassword"));
        assertFalse(policy.passwordValid("Onlylowercase1"));
        assertFalse(policy.passwordValid("AAaa!!password"));
        assertFalse(policy.passwordValid("Q!r7aBc9x-Z9"));
        assertFalse(policy.passwordValid("Q!r73219x-Z9"));
        assertTrue(policy.passwordValid("Aa-long-pword!"));

        var invalid =
                new SecuritySettings(7, false, false, false, true, 2, 4, 2, 4, 600, 60, 3, 5, 3);
        assertThrows(ResponseStatusException.class, invalid::validate);
    }

    @Test
    void verificationPolicySupportsTheDatabaseAlignedUpperBounds() {
        var boundary =
                new SecuritySettings(
                        8, false, false, false, true, 1, 1, 1, 1, 60, 1, 10_000, 10_000, 20);
        assertDoesNotThrow(boundary::validate);

        var tooManyRequests =
                new SecuritySettings(
                        8, false, false, false, true, 1, 1, 1, 1, 60, 1, 10_001, 10_000, 20);
        assertThrows(ResponseStatusException.class, tooManyRequests::validate);

        var tooManyFailures =
                new SecuritySettings(
                        8, false, false, false, true, 1, 1, 1, 1, 60, 1, 10_000, 10_000, 21);
        assertThrows(ResponseStatusException.class, tooManyFailures::validate);
    }
}
