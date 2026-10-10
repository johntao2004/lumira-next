package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.SecretKeys;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PhoneVerificationCodeTest {
    String key(byte fill) {
        var bytes = new byte[32];
        java.util.Arrays.fill(bytes, fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void sixDigitsAreKeyedToAccountAndSurviveKeyRotation() {
        UUID owner = UUID.randomUUID();
        var old = new PhoneVerificationCode(new SecretKeys("old=" + key((byte) 1), "old"));
        var issued = old.issue(owner);
        assertTrue(issued.value().matches("\\d{6}"));
        assertTrue(issued.digest().matches("[a-f0-9]{64}"));
        assertTrue(old.matches(owner, issued.value(), issued.digest()));
        assertFalse(old.matches(UUID.randomUUID(), issued.value(), issued.digest()));
        assertFalse(
                old.matches(
                        owner,
                        issued.value().equals("000000") ? "000001" : "000000",
                        issued.digest()));
        assertFalse(issued.toString().contains(issued.value()));
        var rotated =
                new PhoneVerificationCode(
                        new SecretKeys("old=" + key((byte) 1) + ",new=" + key((byte) 2), "new"));
        assertTrue(rotated.matches(owner, issued.value(), issued.digest()));
        var removed = new PhoneVerificationCode(new SecretKeys("new=" + key((byte) 2), "new"));
        assertFalse(removed.matches(owner, issued.value(), issued.digest()));
    }

    @Test
    void legacyLongCodesRemainValidButInvalidInputsDoNot() {
        var codes = new PhoneVerificationCode(new SecretKeys("test=" + key((byte) 1), "test"));
        var legacy = VerificationToken.issue();
        var owner = UUID.randomUUID();
        assertTrue(codes.matches(owner, legacy.value(), legacy.digest()));
        for (String value : new String[] {"", "12345", "1234567", "123456\n", "abcdef"})
            assertFalse(codes.matches(owner, value, legacy.digest()));
        assertFalse(codes.matches(owner, null, legacy.digest()));
        assertFalse(codes.matches(owner, "123456", null));
    }
}
