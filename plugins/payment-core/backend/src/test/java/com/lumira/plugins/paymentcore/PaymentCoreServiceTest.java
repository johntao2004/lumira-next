package com.lumira.plugins.paymentcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.plugin.sdk.common.PaymentCoreAdminPort;
import com.lumira.plugin.sdk.common.PaymentGatewayPort;
import com.lumira.plugin.sdk.common.PaymentGatewayPort.RefundResult;
import com.lumira.plugin.sdk.common.PaymentGatewayPort.RefundState;
import com.lumira.plugin.sdk.common.PaymentGatewayPort.TradeResult;
import com.lumira.plugin.sdk.common.PaymentGatewayPort.TradeState;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class PaymentCoreServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T02:00:00Z");
    private static final long AMOUNT_FEN = 1250;

    @Test
    void thirdProviderUsesItsOwnPageCallbackAndSharedOrderLifecycle() {
        var h = new Harness("third-pay");
        var created = h.createOrder();
        assertEquals("third-pay", created.providerId());
        assertEquals("PENDING", created.status());
        h.gateway.notification =
                new PaymentGatewayPort.Notification(
                        created.merchantOrderNo(),
                        "synthetic-trade-1",
                        AMOUNT_FEN,
                        "CNY",
                        true,
                        "third-event",
                        NOW);
        h.service.acceptCallback("third-pay", Map.of(), "verified-by-fixture");
        assertEquals("PAID", h.store.findOrder(created.orderId()).orElseThrow().status());
        h.gateway.refundQueryResult =
                new RefundResult(RefundState.SUCCESS, "third-refund", AMOUNT_FEN, NOW, null);
        assertEquals(
                "REFUNDED",
                h.service
                        .refundOrder(
                                "admin",
                                h.page,
                                created.orderId(),
                                new PaymentCoreAdminPort.RefundRequest(
                                        UUID.randomUUID(), "Fixture refund"))
                        .status());
        assertEquals(1, h.gateway.precreateCalls);
        assertEquals(1, h.gateway.refundCalls);
        assertEquals(
                "platform:third-pay:orders-refund",
                h.page.operationPermission(PaymentCoreAdminPort.Operation.REFUND));
        assertThrows(
                ResponseStatusException.class,
                () ->
                        h.service.createOrder(
                                "admin",
                                h.page,
                                new PaymentCoreAdminPort.CreateOrder(
                                        UUID.randomUUID(), "wechat-pay", AMOUNT_FEN, "Mismatch"),
                                "127.0.0.1"));
    }

    @Test
    void subjectBoundaryCountsUtf8BytesAndRejectsNulBeforeReservingOrders() {
        var h = new Harness("alipay-pay");
        for (String subject :
                List.of("a".repeat(127), "中".repeat(42) + "a", "😀".repeat(31) + "abc")) {
            h.service.createOrder(
                    "admin",
                    h.page,
                    new PaymentCoreAdminPort.CreateOrder(
                            UUID.randomUUID(), "alipay-pay", AMOUNT_FEN, subject),
                    "127.0.0.1");
            var error =
                    assertThrows(
                            ResponseStatusException.class,
                            () ->
                                    h.service.createOrder(
                                            "admin",
                                            h.page,
                                            new PaymentCoreAdminPort.CreateOrder(
                                                    UUID.randomUUID(),
                                                    "alipay-pay",
                                                    AMOUNT_FEN,
                                                    subject + "a"),
                                            "127.0.0.1"));
            assertEquals("paymentOrderInvalid", error.getReason());
        }
        var nulError =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                h.service.createOrder(
                                        "admin",
                                        h.page,
                                        new PaymentCoreAdminPort.CreateOrder(
                                                UUID.randomUUID(),
                                                "alipay-pay",
                                                AMOUNT_FEN,
                                                "bad\0title"),
                                        "127.0.0.1"));
        assertEquals("paymentOrderInvalid", nulError.getReason());
        assertEquals(3, h.gateway.precreateCalls);
    }

    @Test
    void refundRejectsNulAndUtf8OverflowBeforeReservingOrCallingGateway() {
        var h = new Harness("alipay-pay");
        var order = h.createOrder();
        h.store.setStatus(order.orderId(), "PAID");
        for (String reason :
                List.of(
                        "bad\0reason",
                        "\0refund",
                        "refund\0",
                        "\0refund\0",
                        "中".repeat(27),
                        "😀".repeat(21))) {
            var error =
                    assertThrows(
                            ResponseStatusException.class,
                            () ->
                                    h.service.refundOrder(
                                            "admin",
                                            h.page,
                                            order.orderId(),
                                            new PaymentCoreAdminPort.RefundRequest(
                                                    UUID.randomUUID(), reason)));
            assertEquals("paymentRefundInvalid", error.getReason());
            assertEquals("PAID", h.store.findOrder(order.orderId()).orElseThrow().status());
        }
        assertEquals(0, h.gateway.refundCalls);
        h.service.refundOrder(
                "admin",
                h.page,
                order.orderId(),
                new PaymentCoreAdminPort.RefundRequest(UUID.randomUUID(), "中".repeat(26) + "ab"));
        assertEquals(1, h.gateway.refundCalls);
    }

    @Test
    void repeatedCreateWithSameKeyAndPayloadCallsProviderOnlyOnceAndDifferentPayloadConflicts() {
        var h = new Harness("alipay-pay");
        var key = UUID.randomUUID();
        var request =
                new PaymentCoreAdminPort.CreateOrder(
                        key, "alipay-pay", AMOUNT_FEN, "Synthetic fixture");

        var first = h.service.createOrder("admin", h.page, request, "127.0.0.1");
        var repeated = h.service.createOrder("admin", h.page, request, "127.0.0.1");

        assertEquals(first.orderId(), repeated.orderId());
        assertEquals(first.merchantOrderNo(), repeated.merchantOrderNo());
        assertEquals(1, h.gateway.precreateCalls);
        var changed =
                new PaymentCoreAdminPort.CreateOrder(
                        key, "alipay-pay", AMOUNT_FEN + 1, "Synthetic fixture");
        assertThrows(
                ResponseStatusException.class,
                () -> h.service.createOrder("admin", h.page, changed, "127.0.0.1"));
        assertEquals(1, h.gateway.precreateCalls);
    }

    @Test
    void timeoutKeepsMerchantOrderNumberAndQueryRecoversPaidState() {
        var h = new Harness("alipay-pay");
        h.gateway.failPrecreate = true;
        var created =
                h.service.createOrder(
                        "admin",
                        h.page,
                        new PaymentCoreAdminPort.CreateOrder(
                                UUID.randomUUID(), "alipay-pay", AMOUNT_FEN, "Synthetic fixture"),
                        "127.0.0.1");
        assertEquals("UNKNOWN", created.status());
        String stableMerchantNo = created.merchantOrderNo();

        h.gateway.failPrecreate = false;
        h.gateway.tradeResult =
                new PaymentGatewayPort.TradeResult(
                        PaymentGatewayPort.TradeState.PAID,
                        "synthetic-trade-1",
                        AMOUNT_FEN,
                        "CNY",
                        NOW,
                        null);
        var recovered = h.service.queryOrder("admin", h.page, created.orderId());

        assertEquals(stableMerchantNo, recovered.merchantOrderNo());
        assertEquals("PAID", recovered.status());
        assertEquals(1, h.gateway.precreateCalls);
        assertEquals(1, h.gateway.queryCalls);
    }

    @Test
    void queryRejectsPaidAmountMismatchWithoutPromotingOrder() {
        var h = new Harness("alipay-pay");
        var created = h.createOrder();
        h.gateway.tradeResult =
                new TradeResult(
                        TradeState.PAID, "synthetic-trade-1", AMOUNT_FEN + 1, "CNY", NOW, null);

        assertThrows(
                ResponseStatusException.class,
                () -> h.service.queryOrder("admin", h.page, created.orderId()));
        assertEquals("PENDING", h.store.findOrder(created.orderId()).orElseThrow().status());
    }

    @Test
    void providerBoundPageCannotReadAnotherProvidersOrder() {
        var wechat = new Harness("wechat-pay");
        var order = wechat.createOrder();
        var alipay = new Harness("alipay-pay");
        alipay.store.seed(wechat.store.findOrder(order.orderId()).orElseThrow());

        var denied =
                assertThrows(
                        ResponseStatusException.class,
                        () -> alipay.service.queryOrder("admin", alipay.page, order.orderId()));

        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, denied.getStatusCode());
        assertEquals(0, alipay.gateway.queryCalls);
        assertEquals("PENDING", alipay.store.findOrder(order.orderId()).orElseThrow().status());
    }

    @Test
    void callbackRejectsWrongProviderAndAmountBeforeChangingOrder() {
        var wechat = new Harness("wechat-pay");
        var wechatOrder = wechat.createOrder();
        var alipay = new Harness("alipay-pay");
        alipay.store.seed(wechat.store.findOrder(wechatOrder.orderId()).orElseThrow());
        alipay.gateway.notification =
                new PaymentGatewayPort.Notification(
                        wechatOrder.merchantOrderNo(),
                        "synthetic-trade-1",
                        AMOUNT_FEN + 1,
                        "CNY",
                        true,
                        "event-1",
                        NOW);

        assertThrows(
                ResponseStatusException.class,
                () ->
                        alipay.service.acceptCallback(
                                "alipay-pay",
                                Map.of("content-type", "application/x-www-form-urlencoded"),
                                "fixture"));
        assertEquals(
                "PENDING", alipay.store.findOrder(wechatOrder.orderId()).orElseThrow().status());

        var alipayOrder = alipay.createOrder();
        alipay.gateway.notification =
                new PaymentGatewayPort.Notification(
                        alipayOrder.merchantOrderNo(),
                        "synthetic-trade-2",
                        AMOUNT_FEN + 1,
                        "CNY",
                        true,
                        "event-2",
                        NOW);
        assertThrows(
                ResponseStatusException.class,
                () ->
                        alipay.service.acceptCallback(
                                "alipay-pay",
                                Map.of("content-type", "application/x-www-form-urlencoded"),
                                "fixture"));
        assertEquals(
                "PENDING", alipay.store.findOrder(alipayOrder.orderId()).orElseThrow().status());
    }

    @Test
    void repeatedRefundQueriesPendingThenSuccessWithoutSendingSecondRefund() {
        var h = new Harness("alipay-pay");
        var created = h.createOrder();
        h.store.setStatus(created.orderId(), "PAID");
        h.gateway.failRefund = true;
        var key = UUID.randomUUID();
        var refund = new PaymentCoreAdminPort.RefundRequest(key, "Synthetic test refund");

        var first = h.service.refundOrder("admin", h.page, created.orderId(), refund);
        String fixedRefundNo = h.store.findOrder(created.orderId()).orElseThrow().outRefundNo();
        assertEquals("REFUND_PENDING", first.status());
        assertEquals("UNKNOWN", first.refundStatus());

        h.gateway.failRefund = false;
        h.gateway.refundQueryResult =
                new PaymentGatewayPort.RefundResult(
                        PaymentGatewayPort.RefundState.PENDING, null, 0, null, null);
        var repeated = h.service.refundOrder("admin", h.page, created.orderId(), refund);
        assertEquals("PENDING", repeated.refundStatus());
        assertEquals(
                fixedRefundNo, h.store.findOrder(created.orderId()).orElseThrow().outRefundNo());
        assertEquals(1, h.gateway.refundCalls);
        assertEquals(1, h.gateway.refundQueryCalls);

        h.gateway.refundQueryResult =
                new PaymentGatewayPort.RefundResult(
                        PaymentGatewayPort.RefundState.SUCCESS,
                        "synthetic-refund-1",
                        AMOUNT_FEN,
                        NOW,
                        null);
        var restored = h.service.queryRefund("admin", h.page, created.orderId());
        assertEquals("REFUNDED", restored.status());
        assertEquals("SUCCESS", restored.refundStatus());
        assertEquals(1, h.gateway.refundCalls);
    }

    private static final class Harness {
        final FakeStore store = new FakeStore();
        final FakeGateway gateway;
        final PaymentCoreService service;
        final PaymentCoreAdminPort.PageContext page;

        Harness(String providerId) {
            gateway = new FakeGateway(providerId);
            page =
                    "wechat-pay".equals(providerId)
                            ? PaymentCoreAdminPort.Page.WECHAT_ORDERS
                            : "alipay-pay".equals(providerId)
                                    ? PaymentCoreAdminPort.Page.ALIPAY_ORDERS
                                    : new PaymentCoreAdminPort.ProviderPage(
                                            providerId, PaymentCoreAdminPort.PageKind.ORDERS);
            var extensions =
                    new PluginExtensionsPort() {
                        @Override
                        public <T, R> List<R> extensions(Class<T> type, Function<T, R> call) {
                            if (!type.isInstance(gateway)) return List.of();
                            return Collections.singletonList(call.apply(type.cast(gateway)));
                        }
                    };
            var admin = mock(AdministrationPort.class);
            when(admin.require(anyString(), anyString())).thenReturn(Set.of("admin"));
            doNothing().when(admin).requireGlobal(anyString(), anyString());
            service =
                    new PaymentCoreService(
                            store, extensions, admin, null, JsonMapper.builder().build());
        }

        PaymentCoreAdminPort.OrderView createOrder() {
            return service.createOrder(
                    "admin",
                    page,
                    new PaymentCoreAdminPort.CreateOrder(
                            UUID.randomUUID(), gateway.providerId, AMOUNT_FEN, "Synthetic fixture"),
                    "127.0.0.1");
        }
    }

    private static final class FakeGateway implements PaymentGatewayPort {
        final String providerId;
        int precreateCalls;
        int queryCalls;
        int refundCalls;
        int refundQueryCalls;
        boolean failPrecreate;
        boolean failRefund;
        PaymentGatewayPort.Notification notification;
        TradeResult tradeResult = new TradeResult(TradeState.PENDING, null, 0, null, null, null);
        RefundResult refundQueryResult = new RefundResult(RefundState.PENDING, null, 0, null, null);

        FakeGateway(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public String id() {
            return providerId;
        }

        @Override
        public String callbackPath() {
            return Set.of("wechat-pay", "alipay-pay").contains(providerId)
                    ? PaymentGatewayPort.super.callbackPath()
                    : "/api/plugins/" + providerId + "/callbacks/" + providerId;
        }

        @Override
        public Set<String> secretFields() {
            return Set.of("credential");
        }

        @Override
        public ValidationResult validate(Settings settings) {
            return new ValidationResult(
                    settings.values().getOrDefault("notifyUrl", "").startsWith("https://")
                                    && settings.secrets().containsKey("credential")
                            ? List.of()
                            : List.of("syntheticInvalid"));
        }

        @Override
        public PrecreateResult precreate(Settings settings, OrderRequest request) {
            precreateCalls++;
            if (failPrecreate) throw new IllegalStateException("synthetic timeout");
            return new PrecreateResult(
                    "synthetic-trade-1", "https://qr.alipay.com/synthetic", NOW.plusSeconds(300));
        }

        @Override
        public TradeResult query(Settings settings, String merchantOrderNo) {
            queryCalls++;
            return tradeResult;
        }

        @Override
        public void close(Settings settings, String merchantOrderNo) {}

        @Override
        public RefundResult refund(Settings settings, RefundRequest request) {
            refundCalls++;
            if (failRefund) throw new IllegalStateException("synthetic timeout");
            return refundQueryResult;
        }

        @Override
        public RefundResult queryRefund(Settings settings, RefundRequest request) {
            refundQueryCalls++;
            return refundQueryResult;
        }

        @Override
        public Notification verifyCallback(
                Settings settings, Map<String, String> headers, String rawBody) {
            return notification;
        }
    }

    private static final class FakeStore extends PaymentCoreStore {
        private final Map<UUID, Order> orders = new LinkedHashMap<>();
        private final Map<UUID, UUID> keys = new LinkedHashMap<>();

        FakeStore() {
            super(null, null, null);
        }

        @Override
        SettingsVersion currentSettings(String providerId) {
            return settings(providerId);
        }

        @Override
        SettingsSnapshot settingsSnapshot(String providerId, long version) {
            return new SettingsSnapshot(
                    settings(providerId), Map.of("credential", "synthetic-secret"));
        }

        @Override
        Optional<Order> findByIdempotencyKey(UUID idempotencyKey) {
            return Optional.ofNullable(orders.get(keys.get(idempotencyKey)));
        }

        void seed(Order order) {
            orders.put(order.orderId(), order);
            keys.put(order.idempotencyKey(), order.orderId());
        }

        @Override
        List<Long> callbackSettingsVersions(String providerId, int limit) {
            return List.of(1L);
        }

        @Override
        Optional<Long> findSettingsVersionByMerchantOrderNo(
                String merchantOrderNo, String providerId) {
            return orders.values().stream()
                    .filter(order -> order.merchantOrderNo().equals(merchantOrderNo))
                    .filter(order -> order.providerId().equals(providerId))
                    .map(Order::settingsVersion)
                    .findFirst();
        }

        @Override
        void applyNotification(
                String providerId,
                long settingsVersion,
                PaymentGatewayPort.Notification notification) {
            var order =
                    orders.values().stream()
                            .filter(
                                    candidate ->
                                            candidate
                                                    .merchantOrderNo()
                                                    .equals(notification.merchantOrderNo()))
                            .findFirst()
                            .orElseThrow();
            requireNotificationIdentity(order, providerId, settingsVersion);
            if (!notification.success()) return;
            requirePaymentMatches(
                    order,
                    notification.providerTradeNo(),
                    notification.amountFen(),
                    notification.currency());
            orders.put(
                    order.orderId(),
                    copy(
                            order,
                            "PAID",
                            order.codeUrl(),
                            notification.providerTradeNo(),
                            NOW,
                            null,
                            null,
                            null,
                            null));
        }

        @Override
        Reservation reserveOrder(
                UUID orderId,
                UUID idempotencyKey,
                String fingerprint,
                String providerId,
                long expectedSettingsVersion,
                String merchantOrderNo,
                long amountFen,
                String subject,
                String actor) {
            var existing = findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) return new Reservation(existing.get(), false);
            var order =
                    new Order(
                            orderId,
                            idempotencyKey,
                            fingerprint,
                            providerId,
                            expectedSettingsVersion,
                            merchantOrderNo,
                            amountFen,
                            "CNY",
                            subject,
                            "CREATING",
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
                            actor,
                            NOW,
                            NOW);
            orders.put(orderId, order);
            keys.put(idempotencyKey, orderId);
            return new Reservation(order, true);
        }

        @Override
        void markPrecreated(
                UUID orderId, String providerTradeNo, String codeUrl, Instant expiresAt) {
            var order = orders.get(orderId);
            orders.put(
                    orderId,
                    copy(order, "PENDING", codeUrl, providerTradeNo, null, null, null, null, null));
        }

        @Override
        void markUnknown(UUID orderId, String code) {
            var order = orders.get(orderId);
            orders.put(
                    orderId,
                    copy(
                            order,
                            "UNKNOWN",
                            order.codeUrl(),
                            order.providerTradeNo(),
                            null,
                            null,
                            null,
                            null,
                            null));
        }

        @Override
        Optional<Order> findOrder(UUID orderId) {
            return Optional.ofNullable(orders.get(orderId));
        }

        @Override
        void applyTradeResult(UUID orderId, TradeResult result) {
            var order = orders.get(orderId);
            if (result.state() == TradeState.PAID) {
                requirePaymentMatches(
                        order, result.providerTradeNo(), result.amountFen(), result.currency());
                orders.put(
                        orderId,
                        copy(
                                order,
                                "PAID",
                                order.codeUrl(),
                                result.providerTradeNo(),
                                NOW,
                                null,
                                null,
                                null,
                                null));
                return;
            }
            var state = nonPaidTradeTransition(order.status(), result.state());
            orders.put(
                    orderId,
                    copy(
                            order,
                            state,
                            order.codeUrl(),
                            order.providerTradeNo(),
                            null,
                            null,
                            null,
                            null,
                            null));
        }

        @Override
        RefundReservation reserveRefund(UUID orderId, UUID key, String reason) {
            var order = orders.get(orderId);
            if ("REFUND_PENDING".equals(order.status())) return new RefundReservation(order, false);
            if (!"PAID".equals(order.status())) throw new IllegalStateException("not refundable");
            var next =
                    copy(
                            order,
                            "REFUND_PENDING",
                            order.codeUrl(),
                            order.providerTradeNo(),
                            null,
                            key,
                            "LR-SYNTHETIC-1",
                            "UNKNOWN",
                            null,
                            null,
                            reason);
            orders.put(orderId, next);
            return new RefundReservation(next, true);
        }

        @Override
        void markRefundUnknown(UUID orderId) {
            var order = orders.get(orderId);
            orders.put(
                    orderId,
                    copy(
                            order,
                            "REFUND_PENDING",
                            order.codeUrl(),
                            order.providerTradeNo(),
                            null,
                            order.refundIdempotencyKey(),
                            order.outRefundNo(),
                            "UNKNOWN",
                            order.providerRefundNo(),
                            null,
                            order.refundReason()));
        }

        @Override
        void applyRefundResult(UUID orderId, RefundResult result) {
            var order = orders.get(orderId);
            if (result.state() == RefundState.SUCCESS) {
                assertEquals(order.amountFen(), result.amountFen());
                orders.put(
                        orderId,
                        copy(
                                order,
                                "REFUNDED",
                                order.codeUrl(),
                                order.providerTradeNo(),
                                null,
                                order.refundIdempotencyKey(),
                                order.outRefundNo(),
                                "SUCCESS",
                                result.providerRefundNo(),
                                NOW,
                                order.refundReason()));
            } else {
                orders.put(
                        orderId,
                        copy(
                                order,
                                "REFUND_PENDING",
                                order.codeUrl(),
                                order.providerTradeNo(),
                                null,
                                order.refundIdempotencyKey(),
                                order.outRefundNo(),
                                "PENDING",
                                result.providerRefundNo(),
                                null,
                                order.refundReason()));
            }
        }

        void setStatus(UUID orderId, String status) {
            var order = orders.get(orderId);
            orders.put(
                    orderId,
                    copy(
                            order,
                            status,
                            order.codeUrl(),
                            order.providerTradeNo(),
                            NOW,
                            null,
                            null,
                            null,
                            null));
        }

        private static SettingsVersion settings(String providerId) {
            return new SettingsVersion(
                    providerId,
                    1,
                    Map.of(
                            "notifyUrl",
                            "https://merchant.example"
                                    + new FakeGateway(providerId).callbackPath()));
        }

        private static Order copy(
                Order order,
                String status,
                String codeUrl,
                String providerTradeNo,
                Instant paidAt,
                UUID refundKey,
                String outRefundNo,
                String refundStatus,
                String providerRefundNo) {
            return copy(
                    order,
                    status,
                    codeUrl,
                    providerTradeNo,
                    paidAt,
                    refundKey,
                    outRefundNo,
                    refundStatus,
                    providerRefundNo,
                    null,
                    order.refundReason());
        }

        private static Order copy(
                Order order,
                String status,
                String codeUrl,
                String providerTradeNo,
                Instant paidAt,
                UUID refundKey,
                String outRefundNo,
                String refundStatus,
                String providerRefundNo,
                Instant refundedAt,
                String refundReason) {
            return new Order(
                    order.orderId(),
                    order.idempotencyKey(),
                    order.requestFingerprint(),
                    order.providerId(),
                    order.settingsVersion(),
                    order.merchantOrderNo(),
                    order.amountFen(),
                    order.currency(),
                    order.subject(),
                    status,
                    codeUrl,
                    providerTradeNo,
                    order.expiresAt(),
                    paidAt == null ? order.paidAt() : paidAt,
                    refundKey == null ? order.refundIdempotencyKey() : refundKey,
                    outRefundNo == null ? order.outRefundNo() : outRefundNo,
                    refundReason,
                    refundStatus == null ? order.refundStatus() : refundStatus,
                    providerRefundNo,
                    order.refundRequestedAt(),
                    refundedAt,
                    order.lastErrorCode(),
                    order.createdBy(),
                    order.createdAt(),
                    NOW);
        }
    }
}
