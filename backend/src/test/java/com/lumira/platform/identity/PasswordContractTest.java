package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.identity.api.PasswordContract;
import org.junit.jupiter.api.Test;

class PasswordContractTest {
    @Test
    void usesUtf8ByteLimitForModernPasswords() {
        assertTrue(PasswordContract.withinMaximum("x".repeat(256)));
        assertFalse(PasswordContract.withinMaximum("x".repeat(257)));
        assertTrue(PasswordContract.withinMaximum("密".repeat(85))); // 255 UTF-8 bytes
        assertFalse(PasswordContract.withinMaximum("密".repeat(86))); // 258 UTF-8 bytes
    }

    @Test
    void preventsBcryptTruncationDuringLegacyReads() {
        assertTrue(PasswordContract.compatibleWithStoredHash("x".repeat(72), "$2b$04$fixture"));
        assertFalse(PasswordContract.compatibleWithStoredHash("x".repeat(73), "$2b$04$fixture"));
        assertTrue(
                PasswordContract.compatibleWithStoredHash(
                        "x".repeat(256), "{argon2id@lumira_v1}$argon2id$fixture"));
    }
}
