package com.lumira.plugins.paymentcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lumira.plugin.sdk.common.PaymentGatewayPort;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PaymentCoreSettingsInputTest {
    @Test
    void secretFieldNamesAreRejectedFromValuesAndUnknownSecretNamesAreRejected() {
        var secretFields = Set.of("merchantPrivateKeyPem", "apiV3Key");
        assertThrows(
                ResponseStatusException.class,
                () ->
                        PaymentCoreService.cleanValues(
                                Map.of("merchantPrivateKeyPem", "private"), secretFields));
        assertThrows(
                ResponseStatusException.class,
                () ->
                        PaymentCoreService.cleanValues(
                                Map.of("MerchantPrivateKeyPem", "private"), secretFields));
        assertThrows(
                ResponseStatusException.class,
                () ->
                        PaymentCoreService.cleanSecrets(
                                Map.of("unknownPrivateValue", "secret"), secretFields));
        assertEquals(
                Map.of("apiV3Key", "synthetic-secret"),
                PaymentCoreService.cleanSecrets(
                        Map.of("apiV3Key", "synthetic-secret"), secretFields));
        assertFalse(
                new PaymentGatewayPort.Settings(
                                Map.of("appId", "fixture"), Map.of("apiV3Key", "synthetic-secret"))
                        .toString()
                        .contains("synthetic-secret"));
    }
}
