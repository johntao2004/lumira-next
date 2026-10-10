package com.lumira.platform.identity.api;

import java.nio.charset.StandardCharsets;

/** Stable password input limits shared by identity adapters and directory creation. */
public final class PasswordContract {
    public static final int MAX_PASSWORD_BYTES = 256;
    public static final int LEGACY_BCRYPT_MAX_PASSWORD_BYTES = 72;

    private PasswordContract() {}

    public static boolean withinMaximum(String value) {
        return value != null && value.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }

    /** Raw BCrypt hashes are kept compatible without permitting BCrypt truncation. */
    public static boolean compatibleWithStoredHash(String value, String encodedHash) {
        if (!withinMaximum(value) || encodedHash == null) return false;
        return !isLegacyBcrypt(encodedHash)
                || value.getBytes(StandardCharsets.UTF_8).length
                        <= LEGACY_BCRYPT_MAX_PASSWORD_BYTES;
    }

    private static boolean isLegacyBcrypt(String encodedHash) {
        return encodedHash.startsWith("{bcrypt}")
                || encodedHash.startsWith("$2a$")
                || encodedHash.startsWith("$2b$")
                || encodedHash.startsWith("$2y$");
    }
}
