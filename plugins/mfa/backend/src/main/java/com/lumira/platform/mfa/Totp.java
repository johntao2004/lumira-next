package com.lumira.platform.mfa;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238: SHA-1, six digits, 30 seconds, one step of clock tolerance. */
final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {}

    static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 255);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(ALPHABET.charAt((buffer >>> bits) & 31));
            }
        }
        if (bits > 0) out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    static String code(byte[] secret, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = h[h.length - 1] & 15;
            int number =
                    ((h[offset] & 127) << 24)
                            | ((h[offset + 1] & 255) << 16)
                            | ((h[offset + 2] & 255) << 8)
                            | (h[offset + 3] & 255);
            int modulus = digits == 8 ? 100000000 : 1000000;
            return String.format(java.util.Locale.ROOT, "%0" + digits + "d", number % modulus);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP unavailable", e);
        }
    }

    static long verify(byte[] secret, String supplied, long now, long lastStep) {
        if (supplied == null || !supplied.matches("[0-9]{6}")) return -1;
        long current = now / 30;
        long matched = -1;
        for (long step = current - 1; step <= current + 1; step++)
            if (step >= 0
                    && step > lastStep
                    && MessageDigest.isEqual(
                            code(secret, step, 6).getBytes(StandardCharsets.US_ASCII),
                            supplied.getBytes(StandardCharsets.US_ASCII))) matched = step;
        return matched;
    }
}
