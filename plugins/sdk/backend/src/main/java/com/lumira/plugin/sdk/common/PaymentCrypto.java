package com.lumira.plugin.sdk.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Small JDK-only primitives shared by payment provider adapters. */
public final class PaymentCrypto {
    private static final int MIN_RSA_BITS = 2048;
    private static final int MAX_PEM_CHARS = 64 * 1024;
    private static final int MAX_CIPHERTEXT_BYTES = 2 * 1024 * 1024;

    private PaymentCrypto() {}

    /** Parses a PKCS#8 or PKCS#1 RSA private key PEM. */
    public static PrivateKey parseRsaPrivateKeyPem(String pem) {
        byte[] original = pemDer(pem, "PRIVATE KEY", "RSA PRIVATE KEY");
        byte[] der = original;
        try {
            if (pem.contains("BEGIN RSA PRIVATE KEY")) der = wrapPkcs1PrivateKey(original);
            PrivateKey key =
                    KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            requireRsaSize(key);
            return key;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid RSA private key PEM", exception);
        } finally {
            java.util.Arrays.fill(original, (byte) 0);
            if (der != original) java.util.Arrays.fill(der, (byte) 0);
        }
    }

    /** Reads an X.509 SubjectPublicKeyInfo RSA public key PEM. */
    public static PublicKey parseRsaPublicKeyPem(String pem) {
        byte[] der = pemDer(pem, "PUBLIC KEY");
        try {
            PublicKey key =
                    KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            requireRsaSize(key);
            return key;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid RSA public key PEM", exception);
        } finally {
            java.util.Arrays.fill(der, (byte) 0);
        }
    }

    public static String signSha256Rsa(PrivateKey privateKey, String message) {
        Objects.requireNonNull(privateKey, "privateKey");
        Objects.requireNonNull(message, "message");
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Unable to sign payment message", exception);
        }
    }

    public static boolean verifySha256Rsa(
            PublicKey publicKey, String message, String base64Signature) {
        Objects.requireNonNull(publicKey, "publicKey");
        Objects.requireNonNull(message, "message");
        if (base64Signature == null
                || base64Signature.isBlank()
                || base64Signature.length() > 8192) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(base64Signature));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }

    /** Decrypts a WeChat APIv3 AEAD_AES_256_GCM resource. */
    public static byte[] decryptAes256Gcm(
            String apiV3Key, String nonce, String associatedData, String base64Ciphertext) {
        if (apiV3Key == null || apiV3Key.getBytes(StandardCharsets.UTF_8).length != 32) {
            throw new IllegalArgumentException("APIv3 key must be 32 UTF-8 bytes");
        }
        if (nonce == null || nonce.getBytes(StandardCharsets.UTF_8).length != 12) {
            throw new IllegalArgumentException("AES-GCM nonce must be 12 UTF-8 bytes");
        }
        if (base64Ciphertext == null || base64Ciphertext.length() > MAX_CIPHERTEXT_BYTES * 2) {
            throw new IllegalArgumentException("Invalid AES-GCM ciphertext");
        }
        byte[] key = apiV3Key.getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = null;
        try {
            ciphertext = Base64.getDecoder().decode(base64Ciphertext);
            if (ciphertext.length < 16 || ciphertext.length > MAX_CIPHERTEXT_BYTES) {
                throw new IllegalArgumentException("Invalid AES-GCM ciphertext size");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
            if (associatedData != null && !associatedData.isEmpty()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unable to authenticate AES-GCM resource", exception);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
            if (ciphertext != null) java.util.Arrays.fill(ciphertext, (byte) 0);
        }
    }

    private static byte[] pemDer(String pem, String... acceptedLabels) {
        if (pem == null || pem.isBlank() || pem.length() > MAX_PEM_CHARS) {
            throw new IllegalArgumentException("Invalid RSA PEM");
        }
        String normalized = pem.replace("\r", "").trim();
        String label = null;
        for (String accepted : acceptedLabels) {
            String begin = "-----BEGIN " + accepted + "-----";
            String end = "-----END " + accepted + "-----";
            if (normalized.startsWith(begin) && normalized.endsWith(end)) {
                label = accepted;
                normalized =
                        normalized.substring(begin.length(), normalized.length() - end.length());
                break;
            }
        }
        if (label == null || normalized.contains("-----")) {
            throw new IllegalArgumentException("Invalid RSA PEM label");
        }
        String payload = normalized.replaceAll("\\s", "");
        if (payload.isEmpty() || payload.length() > MAX_PEM_CHARS) {
            throw new IllegalArgumentException("Invalid RSA PEM payload");
        }
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid RSA PEM payload", exception);
        }
    }

    private static void requireRsaSize(java.security.Key key) {
        if (!(key instanceof RSAKey rsaKey) || rsaKey.getModulus().bitLength() < MIN_RSA_BITS) {
            throw new IllegalArgumentException("RSA key must be at least 2048 bits");
        }
    }

    /** Wraps an OpenSSL PKCS#1 RSAPrivateKey value in a PKCS#8 PrivateKeyInfo. */
    private static byte[] wrapPkcs1PrivateKey(byte[] pkcs1) {
        byte[] algorithmIdentifier =
                new byte[] {
                    0x30,
                    0x0d,
                    0x06,
                    0x09,
                    0x2a,
                    (byte) 0x86,
                    0x48,
                    (byte) 0x86,
                    (byte) 0xf7,
                    0x0d,
                    0x01,
                    0x01,
                    0x01,
                    0x05,
                    0x00
                };
        byte[] privateKey = der(0x04, pkcs1);
        byte[] version = new byte[] {0x02, 0x01, 0x00};
        byte[] content = concat(version, algorithmIdentifier, privateKey);
        try {
            return der(0x30, content);
        } finally {
            java.util.Arrays.fill(privateKey, (byte) 0);
            java.util.Arrays.fill(content, (byte) 0);
        }
    }

    private static byte[] der(int tag, byte[] content) {
        int length = content.length;
        byte[] lengthBytes;
        if (length < 128) {
            lengthBytes = new byte[] {(byte) length};
        } else {
            int count = 0;
            for (int value = length; value != 0; value >>>= 8) count++;
            lengthBytes = new byte[count + 1];
            lengthBytes[0] = (byte) (0x80 | count);
            for (int index = count; index > 0; index--) {
                lengthBytes[index] = (byte) length;
                length >>>= 8;
            }
        }
        return concat(new byte[] {(byte) tag}, lengthBytes, content);
    }

    private static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) length = Math.addExact(length, array.length);
        byte[] output = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, output, offset, array.length);
            offset += array.length;
        }
        return output;
    }
}
