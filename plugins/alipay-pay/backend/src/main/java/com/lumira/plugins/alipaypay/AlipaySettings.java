package com.lumira.plugins.alipaypay;

import com.lumira.plugin.sdk.common.PaymentCrypto;
import com.lumira.plugin.sdk.common.PaymentGatewayPort;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Validates one short-lived payment-core settings snapshot. */
final class AlipaySettings {
    static final String APP_PRIVATE_KEY = "appPrivateKey";
    static final String ALIPAY_PUBLIC_KEY = "alipayPublicKey";

    enum Environment {
        SANDBOX("https://openapi-sandbox.dl.alipaydev.com"),
        PRODUCTION("https://openapi.alipay.com");

        private final String origin;

        Environment(String origin) {
            this.origin = origin;
        }

        String origin() {
            return origin;
        }
    }

    record Configuration(
            Environment environment,
            String appId,
            String sellerId,
            PrivateKey privateKey,
            PublicKey publicKey) {}

    private AlipaySettings() {}

    static Configuration require(PaymentGatewayPort.Settings settings) {
        if (settings == null) throw new IllegalArgumentException("Alipay settings are required");
        Map<String, String> values = settings.values();
        Map<String, String> secrets = settings.secrets();
        Environment environment;
        try {
            environment = Environment.valueOf(required(values, "environment"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("environment must be SANDBOX or PRODUCTION");
        }
        String appId = required(values, "appId");
        if (!appId.matches("[0-9]{16,32}")) throw new IllegalArgumentException("appId is invalid");
        String sellerId = required(values, "sellerId");
        if (!sellerId.matches("2088[0-9]{12}"))
            throw new IllegalArgumentException("sellerId is invalid");
        AlipayGatewayClient.validateNotifyUrl(required(values, "notifyUrl"));
        String privatePem = requiredSecret(secrets, APP_PRIVATE_KEY);
        String publicPem = requiredSecret(secrets, ALIPAY_PUBLIC_KEY);
        if (privatePem.length() > 8192 || publicPem.length() > 8192)
            throw new IllegalArgumentException("RSA key is too large");
        try {
            PrivateKey privateKey = PaymentCrypto.parseRsaPrivateKeyPem(privatePem);
            PublicKey publicKey = PaymentCrypto.parseRsaPublicKeyPem(publicPem);
            return new Configuration(environment, appId, sellerId, privateKey, publicKey);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("RSA keys are invalid");
        }
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

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(key + " is required");
        return value.trim();
    }

    private static String requiredSecret(Map<String, String> secrets, String key) {
        String value = secrets.get(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(key + " is required");
        return value.trim();
    }
}
