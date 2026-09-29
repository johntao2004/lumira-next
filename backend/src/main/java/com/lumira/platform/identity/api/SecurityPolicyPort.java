package com.lumira.platform.identity.api;

/** Authentication policy contract; settings persistence remains outside identity. */
public interface SecurityPolicyPort {
    Policy currentPolicy();

    record Policy(
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
        public static Policy defaults() {
            return new Policy(12, false, false, false, true,
                    30, 120, 10, 60, 600, 60, 10, 10, 5);
        }

        public boolean passwordValid(String value) {
            if (!PasswordContract.withinMaximum(value)
                    || value.codePointCount(0, value.length()) < passwordMinLength) return false;
            boolean upper = false;
            boolean lower = false;
            boolean special = false;
            boolean repeated = false;
            int previous = -1;
            for (int offset = 0; offset < value.length();) {
                int codePoint = value.codePointAt(offset);
                upper |= Character.isUpperCase(codePoint);
                lower |= Character.isLowerCase(codePoint);
                special |= !Character.isLetterOrDigit(codePoint);
                repeated |= previous == codePoint;
                previous = codePoint;
                offset += Character.charCount(codePoint);
            }
            return (!passwordRequireUppercase || upper)
                    && (!passwordRequireLowercase || lower)
                    && (!passwordRequireSpecialCharacter || special)
                    && (passwordAllowConsecutiveCharacters || !repeated);
        }
    }
}
