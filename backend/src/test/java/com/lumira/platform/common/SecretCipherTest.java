package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class SecretCipherTest {
    private static byte[] key(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static final String CONTEXT = "settings:smtp:password";
    private final SecretCipher cipher = new SecretCipher(Map.of("primary", key(1)), "primary");

    @Test
    void roundtripEmptyBinaryAndMaximumSizeWithRandomNonces() {
        for (byte[] plaintext :
                new byte[][] {
                    new byte[0],
                    new byte[] {0, (byte) 255, 1},
                    new byte[SecretCipher.MAXIMUM_PLAINTEXT_BYTES]
                }) {
            String first = cipher.encrypt(CONTEXT, plaintext),
                    second = cipher.encrypt(CONTEXT, plaintext);
            assertNotEquals(first, second);
            assertArrayEquals(plaintext, cipher.decrypt(CONTEXT, first));
            assertArrayEquals(plaintext, cipher.decrypt(CONTEXT, second));
        }
    }

    @Test
    void ciphertextIsBoundToContextKeyAndAuthenticatedHeader() {
        String encrypted =
                cipher.encrypt(CONTEXT, "fixture password".getBytes(StandardCharsets.UTF_8));
        assertThrows(
                IllegalStateException.class,
                () -> cipher.decrypt("settings:sms:password", encrypted));
        var wrongKey = new SecretCipher(Map.of("primary", key(2)), "primary");
        assertThrows(IllegalStateException.class, () -> wrongKey.decrypt(CONTEXT, encrypted));
        var aliases = new SecretCipher(Map.of("primary", key(1), "alias", key(1)), "primary");
        assertThrows(
                IllegalStateException.class,
                () -> aliases.decrypt(CONTEXT, encrypted.replace("v1:primary:", "v1:alias:")));
        assertFalse(encrypted.contains("fixture password"));
    }

    @Test
    void rejectsTamperingInNoncePayloadAndTagWithoutLeakingValues() {
        String encrypted = cipher.encrypt(CONTEXT, new byte[32]);
        String prefix = "v1:primary:";
        byte[] data = Base64.getUrlDecoder().decode(encrypted.substring(prefix.length()));
        for (int position : new int[] {0, 12, data.length - 1}) {
            byte[] changed = data.clone();
            changed[position] ^= 1;
            var error =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    cipher.decrypt(
                                            CONTEXT,
                                            prefix
                                                    + Base64.getUrlEncoder()
                                                            .withoutPadding()
                                                            .encodeToString(changed)));
            assertEquals("Secret cannot be decrypted", error.getMessage());
            assertNull(error.getCause());
        }
    }

    @Test
    void rotationReadsOldKeysButWritesWithTheActiveKey() {
        String old = cipher.encrypt(CONTEXT, new byte[] {4, 5});
        var rotated = new SecretCipher(Map.of("primary", key(1), "next", key(2)), "next");
        assertArrayEquals(new byte[] {4, 5}, rotated.decrypt(CONTEXT, old));
        String fresh = rotated.encrypt(CONTEXT, new byte[] {6});
        assertTrue(fresh.startsWith("v1:next:"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(CONTEXT, fresh));
        assertThrows(
                IllegalStateException.class,
                () -> new SecretCipher(Map.of("next", key(2)), "next").decrypt(CONTEXT, old));
    }

    @Test
    void rejectsMalformedOversizedAndPlaintextEnvelopes() {
        for (String value :
                new String[] {
                    "password",
                    "v2:primary:AA",
                    "v1:missing:AA",
                    "v1:primary:AA",
                    "v1:primary:%%%",
                    "v1:primary:",
                    "x".repeat(90001)
                }) assertThrows(IllegalStateException.class, () -> cipher.decrypt(CONTEXT, value));
        assertThrows(
                IllegalArgumentException.class, () -> cipher.encrypt(CONTEXT, new byte[65537]));
        assertThrows(IllegalArgumentException.class, () -> cipher.encrypt("", new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> cipher.encrypt("smtp\npassword", new byte[0]));
    }

    @Test
    void configurationIsValidatedAndKeyInputIsDefensivelyCopied() {
        assertThrows(IllegalArgumentException.class, () -> new SecretCipher(Map.of(), "primary"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SecretCipher(Map.of("primary", new byte[16]), "primary"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SecretCipher(Map.of("bad:id", key(1)), "bad:id"));
        byte[] original = key(1);
        var instance = new SecretCipher(Map.of("primary", original), "primary");
        Arrays.fill(original, (byte) 0);
        String encrypted = cipher.encrypt(CONTEXT, new byte[] {9});
        assertArrayEquals(new byte[] {9}, instance.decrypt(CONTEXT, encrypted));
    }
}
