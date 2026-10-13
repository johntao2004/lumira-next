package com.lumira.plugins.wechatpay;

import com.lumira.plugin.sdk.common.PaymentCrypto;
import com.lumira.plugin.sdk.common.PaymentGatewayPort;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts one short-lived, core-owned settings snapshot into validated request credentials. */
final class WechatPaySettings {
    static final String MERCHANT_PRIVATE_KEY = "merchantPrivateKeyPem";
    static final String WECHAT_PUBLIC_KEY = "wechatPayPublicKeyPem";
    static final String API_V3_KEY = "apiV3Key";
    private static final String CALLBACK_PATH = "/api/plugins/payment-core/callbacks/wechat-pay";

    record Configuration(
            String appId,
            String merchantId,
            String merchantSerialNumber,
            String wechatPayPublicKeyId,
            String notifyUrl,
            PrivateKey merchantPrivateKey,
            PublicKey wechatPayPublicKey,
            String apiV3Key) {
        @Override
        public String toString() {
            return "Configuration[appId="
                    + appId
                    + ", merchantId="
                    + merchantId
                    + ", credentials=REDACTED]";
        }
    }

    private WechatPaySettings() {}

    static Configuration require(PaymentGatewayPort.Settings settings) {
        if (settings == null)
            throw new IllegalArgumentException("WeChat Pay settings are required");
        Map<String, String> values = settings.values();
        Map<String, String> secrets = settings.secrets();

        String appId = required(values, "appId");
        if (!appId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("appId is invalid");
        String merchantId = required(values, "merchantId");
        if (!merchantId.matches("[0-9]{5,32}"))
            throw new IllegalArgumentException("merchantId is invalid");
        String merchantSerial = required(values, "merchantSerialNumber");
        if (!merchantSerial.matches("(?i)[0-9a-f]{1,64}"))
            throw new IllegalArgumentException("merchant serial number is invalid");
        String publicKeyId = required(values, "wechatPayPublicKeyId");
        if (!publicKeyId.matches("PUB_KEY_ID_[A-Za-z0-9_-]{1,96}"))
            throw new IllegalArgumentException("WeChat Pay public key ID is invalid");
        String notifyUrl = validateNotifyUrl(required(values, "notifyUrl"));

        String privatePem = requiredSecret(secrets, MERCHANT_PRIVATE_KEY, true);
        String publicPem = requiredSecret(secrets, WECHAT_PUBLIC_KEY, true);
        String apiV3Key = requiredSecret(secrets, API_V3_KEY, false);
        if (privatePem.length() > 8_192 || publicPem.length() > 8_192)
            throw new IllegalArgumentException("RSA PEM is too large");
        if (apiV3Key.getBytes(StandardCharsets.UTF_8).length != 32
                || apiV3Key.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("APIv3 key must be exactly 32 UTF-8 bytes");

        PrivateKey merchantPrivateKey = PaymentCrypto.parseRsaPrivateKeyPem(privatePem);
        PublicKey wechatPayPublicKey = PaymentCrypto.parseRsaPublicKeyPem(publicPem);
        return new Configuration(
                appId,
                merchantId,
                merchantSerial.toUpperCase(java.util.Locale.ROOT),
                publicKeyId,
                notifyUrl,
                merchantPrivateKey,
                wechatPayPublicKey,
                apiV3Key);
    }

    static PaymentGatewayPort.ValidationResult validate(PaymentGatewayPort.Settings settings) {
        List<String> errors = new ArrayList<>();
        try {
            require(settings);
        } catch (IllegalArgumentException exception) {
            errors.add(exception.getMessage());
        }
        return new PaymentGatewayPort.ValidationResult(errors);
    }

    static String validateNotifyUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("WeChat Pay callback URL is invalid");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getQuery() != null
                || !CALLBACK_PATH.equals(uri.getRawPath()))
            throw new IllegalArgumentException(
                    "WeChat Pay callback URL must be the HTTPS payment-core callback");
        return uri.toASCIIString();
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank() || value.length() > 2048 || containsControl(value))
            throw new IllegalArgumentException(key + " is required or invalid");
        return value.trim();
    }

    private static String requiredSecret(Map<String, String> values, String key, boolean trim) {
        String value = values.get(key);
        if (value == null || value.isBlank() || value.length() > 8_192)
            throw new IllegalArgumentException(key + " is required or invalid");
        String normalized = trim ? value.trim() : value;
        if (normalized.isBlank()
                || (trim ? containsNonPemControl(normalized) : containsControl(normalized)))
            throw new IllegalArgumentException(key + " is required or invalid");
        return normalized;
    }

    private static boolean containsNonPemControl(String value) {
        return value.codePoints()
                .anyMatch(
                        character ->
                                Character.isISOControl(character)
                                        && character != '\r'
                                        && character != '\n');
    }

    private static boolean containsControl(String value) {
        return value != null && value.codePoints().anyMatch(Character::isISOControl);
    }
}
