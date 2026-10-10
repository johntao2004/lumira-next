package com.lumira.platform.identity;

import com.lumira.platform.common.SecretKeys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Purpose- and session-bound cryptography for short public authentication challenges. */
@Component
public class PublicAuthCrypto {
    private final SecureRandom random = new SecureRandom();
    private final SecretKeys keys;

    public PublicAuthCrypto(SecretKeys keys) {
        this.keys = keys;
    }

    public String issueCode() {
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    public String sessionDigest(String sessionId) {
        if (sessionId == null || sessionId.isBlank())
            throw new IllegalArgumentException("sessionId");
        return keys.require().authenticate("public-auth:session", ascii(sessionId));
    }

    public String sourceDigest(String sourceAddress) {
        if (sourceAddress == null || sourceAddress.isBlank())
            throw new IllegalArgumentException("source");
        return keys.require().authenticate("public-auth:source", ascii(sourceAddress));
    }

    public String contactDigest(PublicAuthPurpose purpose, String channel, String contact) {
        return keys.require()
                .authenticate(
                        "public-auth:contact:" + purpose.name() + ":" + channel,
                        contact.getBytes(StandardCharsets.UTF_8));
    }

    public String codeDigest(
            PublicAuthPurpose purpose,
            UUID challengeId,
            String sessionDigest,
            String contactDigest,
            String code) {
        return keys.require()
                .authenticate(
                        "public-auth:code:"
                                + purpose.name()
                                + ":"
                                + challengeId
                                + ":"
                                + sessionDigest
                                + ":"
                                + contactDigest,
                        ascii(code));
    }

    public boolean matchesCode(
            PublicAuthPurpose purpose,
            UUID challengeId,
            String sessionDigest,
            String contactDigest,
            String code,
            String expected) {
        if (code == null || !code.matches("\\d{6}") || expected == null) return false;
        String actual = codeDigest(purpose, challengeId, sessionDigest, contactDigest, code);
        return java.security.MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII));
    }

    public String encryptContact(UUID challengeId, String contact) {
        return keys.require()
                .encrypt(
                        "public-auth:" + challengeId + ":contact",
                        contact.getBytes(StandardCharsets.UTF_8));
    }

    public String decryptContact(UUID challengeId, String encrypted) {
        byte[] clear = keys.require().decrypt("public-auth:" + challengeId + ":contact", encrypted);
        try {
            return new String(clear, StandardCharsets.UTF_8);
        } finally {
            java.util.Arrays.fill(clear, (byte) 0);
        }
    }

    public String fingerprint(String context, byte[] canonicalRequest) {
        return keys.require().authenticate("public-auth:fingerprint:" + context, canonicalRequest);
    }

    private static byte[] ascii(String value) {
        if (value == null || !value.matches("[\\x20-\\x7e]{1,512}"))
            throw new IllegalArgumentException("invalidPublicAuthValue");
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
