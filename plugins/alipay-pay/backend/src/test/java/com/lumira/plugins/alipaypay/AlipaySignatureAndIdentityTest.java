package com.lumira.plugins.alipaypay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumira.plugin.sdk.common.PaymentCrypto;
import com.lumira.plugin.sdk.common.PaymentGatewayPort;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AlipaySignatureAndIdentityTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String APP_ID = "2021000000000000";
    private static final String SELLER_ID = "2088123456789012";
    private static final String ORDER_NO = "PAY202610100001";
    private static final Map<String, String> CALLBACK_HEADERS =
            Map.of("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
    private static KeyPair merchantKeyPair;
    private static KeyPair alipayPlatformKeyPair;
    private static PaymentGatewayPort.Settings settings;

    @BeforeAll
    static void generateIndependentMerchantAndPlatformKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        merchantKeyPair = generator.generateKeyPair();
        alipayPlatformKeyPair = generator.generateKeyPair();
        settings = settings(merchantKeyPair.getPrivate(), alipayPlatformKeyPair.getPublic());
        assertFalse(merchantKeyPair.getPublic().equals(alipayPlatformKeyPair.getPublic()));
        String probe = "merchant request and platform response use separate keys";
        assertFalse(
                PaymentCrypto.verifySha256Rsa(
                        alipayPlatformKeyPair.getPublic(),
                        probe,
                        PaymentCrypto.signSha256Rsa(merchantKeyPair.getPrivate(), probe)));
        assertTrue(
                PaymentCrypto.verifySha256Rsa(
                        alipayPlatformKeyPair.getPublic(),
                        probe,
                        PaymentCrypto.signSha256Rsa(alipayPlatformKeyPair.getPrivate(), probe)));
        assertTrue(AlipaySettings.validate(settings).valid());
    }

    @Test
    void requestRsa2CanonicalizationIncludesSignTypeAndExcludesOnlySign() {
        Map<String, String> parameters =
                Map.of(
                        "method", "alipay.trade.query",
                        "app_id", APP_ID,
                        "sign_type", "RSA2",
                        "sign", "not-part-of-the-canonical-message",
                        "biz_content", "{\"out_trade_no\":\"" + ORDER_NO + "\"}");
        String canonical = AlipayGatewayClient.canonicalRequest(parameters);

        assertEquals(
                "app_id="
                        + APP_ID
                        + "&biz_content={\"out_trade_no\":\""
                        + ORDER_NO
                        + "\"}"
                        + "&method=alipay.trade.query&sign_type=RSA2",
                canonical);
        assertTrue(
                PaymentCrypto.verifySha256Rsa(
                        merchantKeyPair.getPublic(),
                        canonical,
                        PaymentCrypto.signSha256Rsa(merchantKeyPair.getPrivate(), canonical)));
    }

    @Test
    void sandboxUsesAlipaysFixedOfficialGatewayOrigin() {
        assertEquals(
                "https://openapi-sandbox.dl.alipaydev.com/gateway.do",
                AlipaySettings.Environment.SANDBOX.origin() + AlipayGatewayClient.GATEWAY_PATH);
        assertEquals(
                "https://openapi.alipay.com/gateway.do",
                AlipaySettings.Environment.PRODUCTION.origin() + AlipayGatewayClient.GATEWAY_PATH);
    }

    @Test
    void verifiesTheExactRawResponseObjectInsteadOfReserializedJson() {
        String rawPayload =
                "{ \"code\" : \"10000\", \"qr_code\" : \"https:\\/\\/qr.alipay.com\\/fixture\" }";
        byte[] validEnvelope = signedEnvelope("alipay_trade_precreate_response", rawPayload);

        JsonNode result =
                AlipayJson.verifiedResponse(
                        validEnvelope,
                        "alipay_trade_precreate_response",
                        AlipaySettings.require(settings));

        assertEquals("10000", AlipayJson.text(result, "code"));
        assertEquals("https://qr.alipay.com/fixture", AlipayJson.text(result, "qr_code"));

        String reserializedPayload =
                "{\"code\":\"10000\",\"qr_code\":\"https://qr.alipay.com/fixture\"}";
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AlipayJson.verifiedResponse(
                                signedEnvelope(
                                        "alipay_trade_precreate_response",
                                        rawPayload,
                                        reserializedPayload),
                                "alipay_trade_precreate_response",
                                AlipaySettings.require(settings)));
    }

    @Test
    void rejectsDuplicateResponsePropertiesBeforeTrustingSignature() {
        String duplicated =
                "{\"code\":\"10000\",\"code\":\"10000\",\"qr_code\":\"https://qr.alipay.com/fixture\"}";
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AlipayJson.verifiedResponse(
                                signedEnvelope("alipay_trade_precreate_response", duplicated),
                                "alipay_trade_precreate_response",
                                AlipaySettings.require(settings)));
    }

    @Test
    void verifiesAsyncFormCallbackWithAlipayPlatformKeyAndRejectsChangedIdentity() {
        Map<String, String> valid = callbackFields(APP_ID, SELLER_ID, "23.45");
        PaymentGatewayPort.Notification notification =
                new AlipayGatewayAdapter()
                        .verifyCallback(
                                settings,
                                Map.of(
                                        "Content-Type",
                                        "application/x-www-form-urlencoded; charset=UTF-8"),
                                encodeCallback(valid));

        assertEquals(ORDER_NO, notification.merchantOrderNo());
        assertEquals("2026101000000000001", notification.providerTradeNo());
        assertEquals(2345, notification.amountFen());
        assertEquals("fixture-notify-1", notification.eventId());
        assertTrue(notification.success());

        String wrongSeller = encodeCallback(callbackFields(APP_ID, "2088999999999999", "23.45"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AlipayGatewayAdapter()
                                .verifyCallback(settings, CALLBACK_HEADERS, wrongSeller));
        String wrongApp = encodeCallback(callbackFields("2021000000000001", SELLER_ID, "23.45"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AlipayGatewayAdapter()
                                .verifyCallback(settings, CALLBACK_HEADERS, wrongApp));
        String foreignCurrency = encodeCallback(callbackFields(APP_ID, SELLER_ID, "23.45", "USD"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AlipayGatewayAdapter()
                                .verifyCallback(settings, CALLBACK_HEADERS, foreignCurrency));
    }

    @Test
    void rejectsDuplicateDecodedCallbackFields() {
        String body =
                encodeCallback(callbackFields(APP_ID, SELLER_ID, "23.45")) + "&app_id=" + APP_ID;
        assertThrows(
                IllegalArgumentException.class,
                () -> new AlipayGatewayAdapter().verifyCallback(settings, CALLBACK_HEADERS, body));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AlipayGatewayAdapter()
                                .verifyCallback(
                                        settings,
                                        CALLBACK_HEADERS,
                                        "app_id=" + APP_ID + "&app%5Fid=" + APP_ID));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AlipayGatewayAdapter()
                                .verifyCallback(
                                        settings,
                                        Map.of(),
                                        encodeCallback(
                                                callbackFields(APP_ID, SELLER_ID, "23.45"))));
    }

    @Test
    void signedPrecreateResponseMustMatchTheRequestedOrderAndAmountWhenEchoed() {
        AlipayGatewayAdapter valid =
                new AlipayGatewayAdapter(
                        (config, method, content, notifyUrl) -> {
                            assertEquals("alipay.trade.precreate", method);
                            assertEquals(ORDER_NO, content.get("out_trade_no"));
                            return signedResponse(
                                    method,
                                    Map.of(
                                            "code",
                                            "10000",
                                            "out_trade_no",
                                            ORDER_NO,
                                            "app_id",
                                            APP_ID,
                                            "seller_id",
                                            SELLER_ID,
                                            "total_amount",
                                            "23.45",
                                            "qr_code",
                                            "https://qr.alipay.com/fixture"),
                                    config);
                        });
        var result =
                valid.precreate(
                        settings,
                        new PaymentGatewayPort.OrderRequest(
                                ORDER_NO,
                                2345,
                                "Synthetic fixture",
                                "https://payments.example.test/api/plugins/payment-core/callbacks/alipay-pay",
                                "192.0.2.10"));
        assertEquals("https://qr.alipay.com/fixture", result.codeUrl());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AlipayGatewayAdapter(
                                        (config, method, content, notifyUrl) ->
                                                signedResponse(
                                                        method,
                                                        Map.of(
                                                                "code", "10000",
                                                                "out_trade_no",
                                                                        "SOMEONE_ELSES_ORDER",
                                                                "app_id", APP_ID,
                                                                "seller_id", SELLER_ID,
                                                                "total_amount", "23.45",
                                                                "qr_code",
                                                                        "https://qr.alipay.com/fixture"),
                                                        config))
                                .precreate(settings, request(2345)));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AlipayGatewayAdapter(
                                        (config, method, content, notifyUrl) ->
                                                signedResponse(
                                                        method,
                                                        Map.of(
                                                                "code",
                                                                "10000",
                                                                "out_trade_no",
                                                                ORDER_NO,
                                                                "app_id",
                                                                APP_ID,
                                                                "seller_id",
                                                                SELLER_ID,
                                                                "total_amount",
                                                                "99.00",
                                                                "qr_code",
                                                                "https://qr.alipay.com/fixture"),
                                                        config))
                                .precreate(settings, request(2345)));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AlipayGatewayAdapter(
                                        (config, method, content, notifyUrl) ->
                                                signedResponse(
                                                        method,
                                                        Map.of(
                                                                "code", "10000",
                                                                "out_trade_no", ORDER_NO,
                                                                "app_id", APP_ID,
                                                                "seller_id", "2088999999999999",
                                                                "total_amount", "23.45",
                                                                "qr_code",
                                                                        "https://qr.alipay.com/fixture"),
                                                        config))
                                .precreate(settings, request(2345)));
    }

    @Test
    void queryRequiresTheRequestedOrderAndReturnsVerifiedPaidResult() {
        AlipayGatewayAdapter correct =
                new AlipayGatewayAdapter(
                        (config, method, content, notifyUrl) -> {
                            assertEquals("alipay.trade.query", method);
                            assertEquals(ORDER_NO, content.get("out_trade_no"));
                            return signedResponse(
                                    method,
                                    Map.of(
                                            "code", "10000",
                                            "out_trade_no", ORDER_NO,
                                            "app_id", APP_ID,
                                            "seller_id", SELLER_ID,
                                            "trade_no", "2026101000000000001",
                                            "total_amount", "23.45",
                                            "trade_status", "TRADE_SUCCESS"),
                                    config);
                        });
        var paid = correct.query(settings, ORDER_NO);
        assertEquals(PaymentGatewayPort.TradeState.PAID, paid.state());
        assertEquals(2345, paid.amountFen());

        AlipayGatewayAdapter pending =
                new AlipayGatewayAdapter(
                        (config, method, content, notifyUrl) ->
                                signedResponse(
                                        method,
                                        Map.of(
                                                "code", "10000",
                                                "out_trade_no", ORDER_NO,
                                                "trade_no", "2026101000000000001",
                                                "total_amount", "23.45",
                                                "trade_status", "WAIT_BUYER_PAY"),
                                        config));
        var pendingResult = pending.query(settings, ORDER_NO);
        assertEquals(PaymentGatewayPort.TradeState.PENDING, pendingResult.state());
        assertEquals(0, pendingResult.amountFen());

        AlipayGatewayAdapter wrongOrder =
                new AlipayGatewayAdapter(
                        (config, method, content, notifyUrl) ->
                                signedResponse(
                                        method,
                                        Map.of(
                                                "code", "10000",
                                                "out_trade_no", "OTHER_ORDER",
                                                "trade_no", "2026101000000000001",
                                                "total_amount", "23.45",
                                                "trade_status", "TRADE_SUCCESS"),
                                        config));
        assertThrows(IllegalArgumentException.class, () -> wrongOrder.query(settings, ORDER_NO));

        AlipayGatewayAdapter foreignCurrency =
                new AlipayGatewayAdapter(
                        (config, method, content, notifyUrl) ->
                                signedResponse(
                                        method,
                                        Map.of(
                                                "code", "10000",
                                                "out_trade_no", ORDER_NO,
                                                "trade_no", "2026101000000000001",
                                                "total_amount", "23.45",
                                                "trans_currency", "USD",
                                                "trade_status", "TRADE_SUCCESS"),
                                        config));
        assertThrows(
                IllegalArgumentException.class, () -> foreignCurrency.query(settings, ORDER_NO));
    }

    @Test
    void paidQueryRejectsMalformedAmountAndMismatchedAppAndSeller() {
        assertQueryRejected(
                Map.of(
                        "code", "10000",
                        "out_trade_no", ORDER_NO,
                        "app_id", APP_ID,
                        "seller_id", SELLER_ID,
                        "trade_no", "2026101000000000001",
                        "total_amount", "23.451",
                        "trade_status", "TRADE_SUCCESS"));
        assertQueryRejected(
                Map.of(
                        "code", "10000",
                        "out_trade_no", ORDER_NO,
                        "app_id", "2021000000000001",
                        "seller_id", SELLER_ID,
                        "trade_no", "2026101000000000001",
                        "total_amount", "23.45",
                        "trade_status", "TRADE_SUCCESS"));
        assertQueryRejected(
                Map.of(
                        "code", "10000",
                        "out_trade_no", ORDER_NO,
                        "app_id", APP_ID,
                        "seller_id", "2088999999999999",
                        "trade_no", "2026101000000000001",
                        "total_amount", "23.45",
                        "trade_status", "TRADE_SUCCESS"));
    }

    @Test
    void refundAndRefundQueryRejectWrongOrderRefundNumberOrAmountAndMapPending() {
        PaymentGatewayPort.RefundRequest request =
                new PaymentGatewayPort.RefundRequest(
                        ORDER_NO, "REFUND20261010_1", 2345, "Synthetic test refund");
        AlipayGatewayAdapter wrongRefundOrder =
                new AlipayGatewayAdapter(
                        (config, method, content, notifyUrl) ->
                                signedResponse(
                                        method,
                                        Map.of(
                                                "code", "10000",
                                                "out_trade_no", "OTHER_ORDER",
                                                "refund_fee", "23.45"),
                                        config));
        assertThrows(
                IllegalArgumentException.class, () -> wrongRefundOrder.refund(settings, request));

        AlipayGatewayAdapter wrongRefundAmount =
                new AlipayGatewayAdapter(
                        (config, method, content, notifyUrl) ->
                                signedResponse(
                                        method,
                                        Map.of(
                                                "code", "10000",
                                                "out_trade_no", ORDER_NO,
                                                "refund_fee", "23.44"),
                                        config));
        assertThrows(
                IllegalArgumentException.class, () -> wrongRefundAmount.refund(settings, request));

        AlipayGatewayAdapter pendingRefund =
                new AlipayGatewayAdapter(
                        (config, method, content, notifyUrl) -> {
                            assertEquals("alipay.trade.fastpay.refund.query", method);
                            assertEquals(ORDER_NO, content.get("out_trade_no"));
                            assertEquals("REFUND20261010_1", content.get("out_request_no"));
                            return signedResponse(
                                    method,
                                    Map.of(
                                            "code", "10000",
                                            "out_trade_no", ORDER_NO,
                                            "out_request_no", "REFUND20261010_1",
                                            "refund_status", "REFUND_PROCESSING",
                                            "refund_amount", "23.45"),
                                    config);
                        });
        var pending = pendingRefund.queryRefund(settings, request);
        assertEquals(PaymentGatewayPort.RefundState.PENDING, pending.state());
        assertEquals(0, pending.amountFen());

        AlipayGatewayAdapter wrongRefundNumber =
                new AlipayGatewayAdapter(
                        (config, method, content, notifyUrl) ->
                                signedResponse(
                                        method,
                                        Map.of(
                                                "code", "10000",
                                                "out_trade_no", ORDER_NO,
                                                "out_request_no", "OTHER_REFUND",
                                                "refund_status", "REFUND_SUCCESS",
                                                "refund_amount", "23.45"),
                                        config));
        assertThrows(
                IllegalArgumentException.class,
                () -> wrongRefundNumber.queryRefund(settings, request));

        AlipayGatewayAdapter wrongPendingAmount =
                new AlipayGatewayAdapter(
                        (config, method, content, notifyUrl) ->
                                signedResponse(
                                        method,
                                        Map.of(
                                                "code", "10000",
                                                "out_trade_no", ORDER_NO,
                                                "out_request_no", "REFUND20261010_1",
                                                "refund_status", "REFUND_PROCESSING",
                                                "refund_amount", "22.00"),
                                        config));
        assertThrows(
                IllegalArgumentException.class,
                () -> wrongPendingAmount.queryRefund(settings, request));
    }

    private static void assertQueryRejected(Map<String, String> payload) {
        AlipayGatewayAdapter adapter =
                new AlipayGatewayAdapter(
                        (config, method, content, notifyUrl) ->
                                signedResponse(method, payload, config));
        assertThrows(IllegalArgumentException.class, () -> adapter.query(settings, ORDER_NO));
    }

    @Test
    void mapsCloseRefundAndRefundQueryToSignedAlipayOperations() {
        AlipayGatewayAdapter adapter =
                new AlipayGatewayAdapter(
                        (config, method, content, notifyUrl) -> {
                            switch (method) {
                                case "alipay.trade.close" -> {
                                    assertEquals(ORDER_NO, content.get("out_trade_no"));
                                    return signedResponse(
                                            method,
                                            Map.of("code", "10000", "out_trade_no", ORDER_NO),
                                            config);
                                }
                                case "alipay.trade.refund" -> {
                                    assertEquals(ORDER_NO, content.get("out_trade_no"));
                                    assertEquals("23.45", content.get("refund_amount"));
                                    assertEquals("REFUND20261010_1", content.get("out_request_no"));
                                    return signedResponse(
                                            method,
                                            Map.of(
                                                    "code", "10000",
                                                    "out_trade_no", ORDER_NO,
                                                    "refund_fee", "23.45",
                                                    "gmt_refund_pay", "2026-10-10 12:34:56"),
                                            config);
                                }
                                case "alipay.trade.fastpay.refund.query" -> {
                                    assertEquals(ORDER_NO, content.get("out_trade_no"));
                                    assertEquals("REFUND20261010_1", content.get("out_request_no"));
                                    return signedResponse(
                                            method,
                                            Map.of(
                                                    "code", "10000",
                                                    "out_trade_no", ORDER_NO,
                                                    "out_request_no", "REFUND20261010_1",
                                                    "refund_status", "REFUND_SUCCESS",
                                                    "refund_amount", "23.45",
                                                    "gmt_refund_pay", "2026-10-10 12:34:56"),
                                            config);
                                }
                                default ->
                                        throw new AssertionError(
                                                "Unexpected synthetic Alipay method: " + method);
                            }
                        });

        adapter.close(settings, ORDER_NO);
        PaymentGatewayPort.RefundRequest request =
                new PaymentGatewayPort.RefundRequest(
                        ORDER_NO, "REFUND20261010_1", 2345, "Synthetic test refund");
        var refund = adapter.refund(settings, request);
        var refundQuery = adapter.queryRefund(settings, request);

        assertEquals(PaymentGatewayPort.RefundState.SUCCESS, refund.state());
        assertEquals(2345, refund.amountFen());
        assertEquals("REFUND20261010_1", refund.providerRefundNo());
        assertEquals(PaymentGatewayPort.RefundState.SUCCESS, refundQuery.state());
        assertEquals(2345, refundQuery.amountFen());
    }

    private static PaymentGatewayPort.OrderRequest request(long amountFen) {
        return new PaymentGatewayPort.OrderRequest(
                ORDER_NO,
                amountFen,
                "Synthetic fixture",
                "https://payments.example.test/api/plugins/payment-core/callbacks/alipay-pay",
                "192.0.2.10");
    }

    private static PaymentGatewayPort.Settings settings(
            PrivateKey merchantPrivateKey, PublicKey alipayPublicKey) {
        return new PaymentGatewayPort.Settings(
                Map.of(
                        "environment",
                        "SANDBOX",
                        "appId",
                        APP_ID,
                        "sellerId",
                        SELLER_ID,
                        "notifyUrl",
                        "https://payments.example.test/api/plugins/payment-core/callbacks/alipay-pay"),
                Map.of(
                        AlipaySettings.APP_PRIVATE_KEY,
                                pem("PRIVATE KEY", merchantPrivateKey.getEncoded()),
                        AlipaySettings.ALIPAY_PUBLIC_KEY,
                                pem("PUBLIC KEY", alipayPublicKey.getEncoded())));
    }

    private static String pem(String label, byte[] der) {
        String encoded = java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + encoded + "\n-----END " + label + "-----";
    }

    private static byte[] signedEnvelope(String responseName, String rawResponse) {
        return signedEnvelope(responseName, rawResponse, rawResponse);
    }

    private static byte[] signedEnvelope(
            String responseName, String rawResponse, String signedPayload) {
        String signature =
                PaymentCrypto.signSha256Rsa(alipayPlatformKeyPair.getPrivate(), signedPayload);
        String envelope =
                "{\"" + responseName + "\":" + rawResponse + ",\"sign\":\"" + signature + "\"}";
        return envelope.getBytes(StandardCharsets.UTF_8);
    }

    private static JsonNode signedResponse(
            String method, Map<String, String> fields, AlipaySettings.Configuration config) {
        try {
            String rawResponse = JSON.writeValueAsString(fields);
            return AlipayJson.verifiedResponse(
                    signedEnvelope(AlipayGatewayClient.responseName(method), rawResponse),
                    AlipayGatewayClient.responseName(method),
                    config);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new AssertionError("Synthetic response fixture could not be encoded", exception);
        }
    }

    private static Map<String, String> callbackFields(
            String appId, String sellerId, String amount) {
        return callbackFields(appId, sellerId, amount, null);
    }

    private static Map<String, String> callbackFields(
            String appId, String sellerId, String amount, String currency) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("app_id", appId);
        fields.put("seller_id", sellerId);
        fields.put("out_trade_no", ORDER_NO);
        fields.put("trade_no", "2026101000000000001");
        fields.put("total_amount", amount);
        fields.put("trade_status", "TRADE_SUCCESS");
        fields.put("notify_id", "fixture-notify-1");
        fields.put("notify_time", "2026-10-10 12:34:56");
        if (currency != null) fields.put("trans_currency", currency);
        fields.put("sign_type", "RSA2");
        fields.put(
                "sign",
                PaymentCrypto.signSha256Rsa(
                        alipayPlatformKeyPair.getPrivate(), callbackCanonical(fields)));
        return fields;
    }

    private static String callbackCanonical(Map<String, String> fields) {
        TreeMap<String, String> sorted = new TreeMap<>();
        fields.forEach(
                (key, value) -> {
                    if (!key.equals("sign") && !key.equals("sign_type") && !value.isEmpty())
                        sorted.put(key, value);
                });
        List<String> parts = new ArrayList<>();
        sorted.forEach((key, value) -> parts.add(key + "=" + value));
        return String.join("&", parts);
    }

    private static String encodeCallback(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(
                        entry ->
                                URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                                        + "="
                                        + URLEncoder.encode(
                                                entry.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
    }
}
