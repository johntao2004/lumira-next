package com.lumira.platform.mfa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TotpTest {
    @Test
    void matchesAllRfc6238Sha1Vectors() {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        long[] times = {59, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000L};
        String[] expected = {
            "94287082", "07081804", "14050471", "89005924", "69279037", "65353130"
        };
        for (int i = 0; i < times.length; i++)
            assertEquals(expected[i], Totp.code(secret, times[i] / 30, 8));
    }

    @Test
    void windowAndReplayAreEnforced() {
        byte[] secret = "a random shared secret".getBytes(StandardCharsets.UTF_8);
        long step = 12345;
        String current = Totp.code(secret, step, 6);
        assertEquals(step, Totp.verify(secret, current, step * 30, -1));
        assertEquals(-1, Totp.verify(secret, current, step * 30, step));
        assertEquals(step - 1, Totp.verify(secret, Totp.code(secret, step - 1, 6), step * 30, -1));
        assertEquals(step + 1, Totp.verify(secret, Totp.code(secret, step + 1, 6), step * 30, -1));
        assertEquals(-1, Totp.verify(secret, Totp.code(secret, step - 2, 6), step * 30, -1));
        assertEquals(-1, Totp.verify(secret, "12345", step * 30, -1));
        assertEquals(-1, Totp.verify(secret, null, step * 30, -1));
    }

    @Test
    void base32ProvisioningHasNoPadding() {
        assertEquals(
                "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
                Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII)));
    }
}
