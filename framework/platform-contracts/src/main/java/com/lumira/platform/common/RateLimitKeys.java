package com.lumira.platform.common;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/**
 * 保持限流键格式兼容；存储与清理仍由宿主负责。 Preserves rate-limit key compatibility; storage and cleanup remain
 * host-owned.
 */
public final class RateLimitKeys {
    private RateLimitKeys() {}

    public static String key(String namespace, String value) {
        if (!namespace.matches("[a-z-]{1,15}"))
            throw new IllegalArgumentException("Invalid rate namespace");
        try {
            return namespace
                    + ":"
                    + HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
