package com.lumira.plugins.paymentcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lumira.plugin.sdk.common.PaymentGatewayPort;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PaymentCoreStoreStateTest {
    @Test
    void notificationRejectsWrongProviderAndWrongSettingsSnapshot() {
        var order = order("alipay-pay", 4);
        assertThrows(
                ResponseStatusException.class,
                () -> PaymentCoreStore.requireNotificationIdentity(order, "wechat-pay", 4));
        assertThrows(
                ResponseStatusException.class,
                () -> PaymentCoreStore.requireNotificationIdentity(order, "alipay-pay", 3));
    }

    @Test
    void notificationRejectsAmountCurrencyAndTradeIdentityMismatch() {
        var order = order("alipay-pay", 4);
        assertThrows(
                ResponseStatusException.class,
                () -> PaymentCoreStore.requirePaymentMatches(order, "synthetic-trade", 999, "CNY"));
        assertThrows(
                ResponseStatusException.class,
                () ->
                        PaymentCoreStore.requirePaymentMatches(
                                order, "synthetic-trade", 1250, "USD"));
        assertThrows(
                ResponseStatusException.class,
                () -> PaymentCoreStore.requirePaymentMatches(order, "", 1250, "CNY"));
        PaymentCoreStore.requirePaymentMatches(order, "synthetic-trade", 1250, "CNY");
    }

    @Test
    void verifiedPaidStateCannotBeDowngradedByLaterProviderQueries() {
        assertEquals(
                "PAID",
                PaymentCoreStore.nonPaidTradeTransition(
                        "PAID", PaymentGatewayPort.TradeState.PENDING));
        assertEquals(
                "PAID",
                PaymentCoreStore.nonPaidTradeTransition(
                        "PAID", PaymentGatewayPort.TradeState.CLOSED));
        assertEquals(
                "PAID",
                PaymentCoreStore.nonPaidTradeTransition(
                        "PAID", PaymentGatewayPort.TradeState.FAILED));
        assertEquals(
                "REFUND_PENDING",
                PaymentCoreStore.nonPaidTradeTransition(
                        "REFUND_PENDING", PaymentGatewayPort.TradeState.CLOSED));
        assertEquals(
                "REFUNDED",
                PaymentCoreStore.nonPaidTradeTransition(
                        "REFUNDED", PaymentGatewayPort.TradeState.FAILED));
        assertEquals(
                "PENDING",
                PaymentCoreStore.nonPaidTradeTransition(
                        "UNKNOWN", PaymentGatewayPort.TradeState.PENDING));
    }

    private static PaymentCoreStore.Order order(String providerId, long settingsVersion) {
        var now = Instant.parse("2026-10-11T02:00:00Z");
        return new PaymentCoreStore.Order(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                providerId,
                settingsVersion,
                "LM000000000000000000000000000000",
                1250,
                "CNY",
                "Synthetic fixture",
                "PENDING",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "test",
                now,
                now);
    }
}
