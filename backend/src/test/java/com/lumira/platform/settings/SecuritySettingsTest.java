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
        var policy = new SecuritySettings(
                12, true, true, true, false,
                2, 4, 2, 4, 600, 60, 3, 5, 3);
        policy.validate();
        assertFalse(policy.passwordValid("onlylowercasepassword"));
        assertFalse(policy.passwordValid("Onlylowercasepassword"));
        assertFalse(policy.passwordValid("Onlylowercase1"));
        assertFalse(policy.passwordValid("AAaa!!password"));
        assertTrue(policy.passwordValid("Aa-long-pword!"));

        var invalid = new SecuritySettings(
                7, false, false, false, true,
                2, 4, 2, 4, 600, 60, 3, 5, 3);
        assertThrows(ResponseStatusException.class, invalid::validate);
    }
}
