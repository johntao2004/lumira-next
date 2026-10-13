package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class PluginPaymentSecretScopeTest {
    private static PluginManifest.DataSet secretScope(String context, String column) {
        return new PluginManifest.DataSet("platform_secret", "payment secrets", column, context);
    }

    @Test
    void paymentCoreCanDeleteOnlyItsTwoExactProviderSecretContexts() {
        assertDoesNotThrow(
                () ->
                        PluginDataRemoval.validate(
                                "payment-core",
                                List.of(
                                        secretScope(
                                                "plugin:payment-core/wechat-pay", "secret_context"),
                                        secretScope(
                                                "plugin:payment-core/alipay-pay",
                                                "secret_context"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PluginDataRemoval.validate(
                                "payment-core",
                                List.of(
                                        secretScope(
                                                "plugin:payment-core/other", "secret_context"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PluginDataRemoval.validate(
                                "payment-core",
                                List.of(
                                        secretScope(
                                                "plugin:payment-core/wechat-pay", "updated_at"))));
    }
}
