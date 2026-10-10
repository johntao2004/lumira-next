package com.lumira.platform.settings;

import com.lumira.platform.identity.api.PasswordContract;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runtime security policy shared by authentication and verification flows.
 *
 * <p>Session cookie lifetime and a CAPTCHA provider are deliberately not part of this record: the
 * scaffold uses Spring Session and does not ship a CAPTCHA provider. Exposing settings that cannot
 * take effect would make the administration screen misleading.
 */
public record SecuritySettings(
        Integer passwordMinLength,
        Boolean passwordRequireUppercase,
        Boolean passwordRequireLowercase,
        Boolean passwordRequireSpecialCharacter,
        Boolean passwordAllowConsecutiveCharacters,
        Integer loginAccountPerMinute,
        Integer loginSourcePerMinute,
        Integer recoveryAccountPerMinute,
        Integer recoverySourcePerMinute,
        Integer verificationCodeExpireSeconds,
        Integer verificationCodeCooldownSeconds,
        Integer verificationRequestsPerMinute,
        Integer verificationDailyLimit,
        Integer verificationFailureLimit) {

    public static SecuritySettings defaults() {
        return new SecuritySettings(
                12, false, false, false, true, 30, 120, 10, 60, 600, 60, 10, 10, 5);
    }

    public void validate() {
        if (passwordMinLength == null
                || passwordMinLength < 8
                || passwordMinLength > PasswordContract.MAX_PASSWORD_BYTES
                || passwordRequireUppercase == null
                || passwordRequireLowercase == null
                || passwordRequireSpecialCharacter == null
                || passwordAllowConsecutiveCharacters == null
                || loginAccountPerMinute == null
                || loginSourcePerMinute == null
                || recoveryAccountPerMinute == null
                || recoverySourcePerMinute == null
                || verificationCodeExpireSeconds == null
                || verificationCodeCooldownSeconds == null
                || verificationRequestsPerMinute == null
                || verificationDailyLimit == null
                || verificationFailureLimit == null) {
            invalid();
        }
        bounded(loginAccountPerMinute, 1, 10_000);
        bounded(loginSourcePerMinute, 1, 10_000);
        bounded(recoveryAccountPerMinute, 1, 10_000);
        bounded(recoverySourcePerMinute, 1, 10_000);
        bounded(verificationCodeExpireSeconds, 60, 3_600);
        bounded(verificationCodeCooldownSeconds, 1, 3_600);
        bounded(verificationRequestsPerMinute, 1, 10_000);
        bounded(verificationDailyLimit, 1, 10_000);
        bounded(verificationFailureLimit, 1, 20);
        if (verificationCodeCooldownSeconds > verificationCodeExpireSeconds
                || loginSourcePerMinute < loginAccountPerMinute
                || recoverySourcePerMinute < recoveryAccountPerMinute) {
            invalid();
        }
    }

    /** Validate a new password against the currently effective policy. */
    public boolean passwordValid(String value) {
        return policy().passwordValid(value);
    }

    /** The authentication contract owns the shared password evaluation algorithm. */
    public SecurityPolicyPort.Policy policy() {
        return new SecurityPolicyPort.Policy(
                passwordMinLength,
                passwordRequireUppercase,
                passwordRequireLowercase,
                passwordRequireSpecialCharacter,
                passwordAllowConsecutiveCharacters,
                loginAccountPerMinute,
                loginSourcePerMinute,
                recoveryAccountPerMinute,
                recoverySourcePerMinute,
                verificationCodeExpireSeconds,
                verificationCodeCooldownSeconds,
                verificationRequestsPerMinute,
                verificationDailyLimit,
                verificationFailureLimit);
    }

    private static void bounded(Integer value, int minimum, int maximum) {
        if (value < minimum || value > maximum) invalid();
    }

    private static void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidSecurity");
    }
}
