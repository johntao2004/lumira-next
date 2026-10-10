package com.lumira.platform.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Creates an in-memory proof that a password provider verified a particular stored hash and
 * credential epoch. The HMAC key is process-local because the proof is consumed during the same
 * request and is never stored in a session or database.
 */
final class PasswordCredentialProof {
    record Evidence(String hashFingerprint, UUID primaryCredentialRevision) {}

    private final byte[] key = new byte[32];

    PasswordCredentialProof() {
        new SecureRandom().nextBytes(key);
    }

    Evidence capture(String encodedHash, UUID primaryCredentialRevision) {
        if (encodedHash == null || primaryCredentialRevision == null) {
            throw new IllegalArgumentException(
                    "Password proof evidence requires a hash and credential epoch");
        }
        return new Evidence(fingerprint(encodedHash), primaryCredentialRevision);
    }

    boolean matches(String fingerprint, String encodedHash) {
        if (fingerprint == null || encodedHash == null) return false;
        byte[] expected;
        try {
            expected = Base64.getUrlDecoder().decode(fingerprint);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        return MessageDigest.isEqual(expected, mac(encodedHash));
    }

    private String fingerprint(String encodedHash) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac(encodedHash));
    }

    private byte[] mac(String encodedHash) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(encodedHash.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException("Password proof fingerprint unavailable", unavailable);
        }
    }
}
