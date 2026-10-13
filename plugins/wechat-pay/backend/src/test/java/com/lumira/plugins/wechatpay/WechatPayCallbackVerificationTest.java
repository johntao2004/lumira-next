package com.lumira.plugins.wechatpay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumira.plugin.sdk.common.PaymentCrypto;
import com.lumira.plugin.sdk.common.PaymentGatewayPort;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WechatPayCallbackVerificationTest {
    private static final String APP_ID = "wx-fixture-app-123";
    private static final String MERCHANT_ID = "1900000109";
    private static final String MERCHANT_SERIAL = "0123456789ABCDEF";
    private static final String PUBLIC_KEY_ID = "PUB_KEY_ID_FIXTURE_0001";
    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";
    private static final String MERCHANT_ORDER_NO = "LM0123456789ABCDEF0123456789ABCD";
    private static final String TRANSACTION_ID = "4200000000202610111234567890";
    private static final String RESOURCE_NONCE = "123456789012";
    private static final String RESOURCE_AAD = "fixture-transaction";
    private static final Instant NOW = Instant.parse("2026-10-11T02:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static KeyPair merchantKeyPair;
    private static KeyPair wechatPlatformKeyPair;
    private static WechatPayGateway gateway;
    private static PaymentGatewayPort.Settings settings;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        merchantKeyPair = generator.generateKeyPair();
        wechatPlatformKeyPair = generator.generateKeyPair();
        assertNotEquals(
                merchantKeyPair.getPublic(),
                wechatPlatformKeyPair.getPublic(),
                "The merchant signing key and WeChat platform verification key must be independent"
                        + " pairs");
        settings = settings(merchantKeyPair, wechatPlatformKeyPair, API_V3_KEY);
        gateway =
                new WechatPayGateway(
                        new com.lumira.plugin.sdk.common.PaymentHttpClient(
                                java.net.URI.create("https://api.mch.weixin.qq.com"),
                                java.time.Duration.ofSeconds(1),
                                java.time.Duration.ofSeconds(1),
                                1024),
                        CLOCK);
    }

    @AfterAll
    static void closeGateway() {
        gateway.close();
    }

    @Test
    void decryptsAndAcceptsOnlyAValidSignedNativeSuccessNotification() throws Exception {
        Fixture fixture = fixture(Transaction.valid(), NOW.getEpochSecond(), null);

        PaymentGatewayPort.Notification notification =
                gateway.verifyCallback(settings, fixture.headers(), fixture.body());

        assertEquals(MERCHANT_ORDER_NO, notification.merchantOrderNo());
        assertEquals(TRANSACTION_ID, notification.providerTradeNo());
        assertEquals(1234, notification.amountFen());
        assertEquals("CNY", notification.currency());
        assertTrue(notification.success());
        assertEquals("EV-WECHAT-FIXTURE-1", notification.eventId());
        assertEquals(Instant.parse("2026-10-11T01:59:00Z"), notification.occurredAt());
    }

    @Test
    void rejectsBodyTamperingAndForgedSignatures() throws Exception {
        Fixture fixture = fixture(Transaction.valid(), NOW.getEpochSecond(), null);
        String forgedBody = fixture.body().replace("Synthetic test callback", "Forged callback");

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.verifyCallback(settings, fixture.headers(), forgedBody));

        Map<String, String> forgedHeaders = new LinkedHashMap<>(fixture.headers());
        forgedHeaders.put("Wechatpay-Signature", Base64.getEncoder().encodeToString(new byte[256]));
        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.verifyCallback(settings, forgedHeaders, fixture.body()));
    }

    @Test
    void rejectsUnknownPublicKeySerialAndStaleSignedTimestamp() throws Exception {
        Fixture fixture = fixture(Transaction.valid(), NOW.getEpochSecond(), null);
        Map<String, String> wrongSerial = new LinkedHashMap<>(fixture.headers());
        wrongSerial.put("Wechatpay-Serial", "PUB_KEY_ID_WRONG_KEY");
        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.verifyCallback(settings, wrongSerial, fixture.body()));

        Fixture stale = fixture(Transaction.valid(), NOW.getEpochSecond() - 301, null);
        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.verifyCallback(settings, stale.headers(), stale.body()));
    }

    @Test
    void rejectsMerchantAppOrderAmountCurrencyAndEventMismatches() throws Exception {
        assertRejected(Transaction.valid().withAppId("wx-other-app"));
        assertRejected(Transaction.valid().withMerchantId("1900000999"));
        assertRejected(Transaction.valid().withOrderNo("invalid order no"));
        assertRejected(Transaction.valid().withAmount(0));
        assertRejected(Transaction.valid().withAmount(Integer.MAX_VALUE + 1L));
        assertRejected(Transaction.valid().withCurrency("USD"));
        assertRejected(Transaction.valid().withTradeType("JSAPI"));
        assertRejected(Transaction.valid().withTradeState("NOTPAY"));

        Fixture wrongEvent =
                fixture(Transaction.valid(), NOW.getEpochSecond(), "TRANSACTION.CLOSED");
        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.verifyCallback(settings, wrongEvent.headers(), wrongEvent.body()));
    }

    @Test
    void returnsSignedOrderIdentityForCoreToCorrelate() throws Exception {
        String otherValidOrderNo = "LM000000000000000000000000000001";
        Fixture fixture =
                fixture(
                        Transaction.valid().withOrderNo(otherValidOrderNo),
                        NOW.getEpochSecond(),
                        null);

        PaymentGatewayPort.Notification notification =
                gateway.verifyCallback(settings, fixture.headers(), fixture.body());

        // The adapter has no local order snapshot; payment-core must match this ID and amount to
        // its stored order.
        assertEquals(otherValidOrderNo, notification.merchantOrderNo());
    }

    @Test
    void rejectsInvalidGcmAuthenticationAndDuplicateJsonKeys() throws Exception {
        Fixture badTag = fixture(Transaction.valid(), NOW.getEpochSecond(), null, true);
        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.verifyCallback(settings, badTag.headers(), badTag.body()));

        Fixture duplicate = fixture(Transaction.valid(), NOW.getEpochSecond(), null, false, true);
        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.verifyCallback(settings, duplicate.headers(), duplicate.body()));
    }

    @Test
    void settingsKeepMerchantAndPlatformRsaPairsSeparate() {
        assertTrue(gateway.validate(settings).valid());
        Map<String, String> mismatchedValues = new LinkedHashMap<>(settings.values());
        mismatchedValues.put("wechatPayPublicKeyId", "PUB_KEY_ID_ANOTHER_PLATFORM_KEY");
        assertTrue(
                gateway.validate(
                                new PaymentGatewayPort.Settings(
                                        mismatchedValues, settings.secrets()))
                        .valid());
        assertEquals(
                java.util.Set.of("merchantPrivateKeyPem", "wechatPayPublicKeyPem", "apiV3Key"),
                gateway.secretFields());
    }

    @Test
    void acceptsOfficialNativeCodeUrlPathsAndRejectsLookalikes() {
        WechatPayGateway.validateCodeUrl("weixin://wxpay/bizpayurl?pr=fixture");
        WechatPayGateway.validateCodeUrl("weixin://wxpay/bizpayurl/up?pr=fixture&groupid=00");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WechatPayGateway.validateCodeUrl(
                                "weixin://wxpay.evil.test/bizpayurl/up?pr=fixture"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WechatPayGateway.validateCodeUrl(
                                "weixin://wxpay/bizpayurl/other?pr=fixture"));
    }

    private static void assertRejected(Transaction transaction) throws Exception {
        Fixture fixture = fixture(transaction, NOW.getEpochSecond(), null);
        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.verifyCallback(settings, fixture.headers(), fixture.body()));
    }

    private static Fixture fixture(Transaction transaction, long timestamp, String eventType)
            throws Exception {
        return fixture(transaction, timestamp, eventType, false, false);
    }

    private static Fixture fixture(
            Transaction transaction, long timestamp, String eventType, boolean corruptTag)
            throws Exception {
        return fixture(transaction, timestamp, eventType, corruptTag, false);
    }

    private static Fixture fixture(
            Transaction transaction,
            long timestamp,
            String eventType,
            boolean corruptTag,
            boolean duplicateKey)
            throws Exception {
        byte[] nonce = RESOURCE_NONCE.getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(API_V3_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(RESOURCE_AAD.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(JSON.writeValueAsBytes(transaction.asMap()));
        if (corruptTag) encrypted[encrypted.length - 1] ^= 0x01;
        String ciphertext = Base64.getEncoder().encodeToString(encrypted);
        Map<String, Object> resource =
                Map.of(
                        "original_type", "transaction",
                        "algorithm", "AEAD_AES_256_GCM",
                        "ciphertext", ciphertext,
                        "associated_data", RESOURCE_AAD,
                        "nonce", RESOURCE_NONCE);
        String serialized;
        if (duplicateKey) {
            serialized =
                    "{\"id\":\"EV-WECHAT-FIXTURE-1\",\"id\":\"EV-WECHAT-FIXTURE-1\","
                            + "\"create_time\":\"2026-10-11T01:59:00+00:00\","
                            + "\"event_type\":\"TRANSACTION.SUCCESS\",\"resource\":"
                            + JSON.writeValueAsString(resource)
                            + "}";
        } else {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("id", "EV-WECHAT-FIXTURE-1");
            envelope.put("create_time", "2026-10-11T01:59:00+00:00");
            envelope.put("event_type", eventType == null ? "TRANSACTION.SUCCESS" : eventType);
            envelope.put("resource_type", "encrypt-resource");
            envelope.put("summary", "Synthetic test callback");
            envelope.put("resource", resource);
            serialized = JSON.writeValueAsString(envelope);
        }
        String headerNonce = "signed-header-nonce";
        String signature =
                PaymentCrypto.signSha256Rsa(
                        wechatPlatformKeyPair.getPrivate(),
                        timestamp + "\n" + headerNonce + "\n" + serialized + "\n");
        Map<String, String> headers =
                Map.of(
                        "Wechatpay-Timestamp", Long.toString(timestamp),
                        "Wechatpay-Nonce", headerNonce,
                        "Wechatpay-Serial", PUBLIC_KEY_ID,
                        "Wechatpay-Signature", signature);
        java.util.Arrays.fill(encrypted, (byte) 0);
        return new Fixture(serialized, headers);
    }

    private static PaymentGatewayPort.Settings settings(
            KeyPair merchant, KeyPair platform, String apiV3Key) throws Exception {
        Map<String, String> values =
                Map.of(
                        "appId", APP_ID,
                        "merchantId", MERCHANT_ID,
                        "merchantSerialNumber", MERCHANT_SERIAL,
                        "wechatPayPublicKeyId", PUBLIC_KEY_ID,
                        "notifyUrl",
                                "https://pay.example.test/api/plugins/payment-core/callbacks/wechat-pay");
        Map<String, String> secrets =
                Map.of(
                        "merchantPrivateKeyPem",
                                pem("PRIVATE KEY", merchant.getPrivate().getEncoded()),
                        "wechatPayPublicKeyPem",
                                pem("PUBLIC KEY", platform.getPublic().getEncoded()),
                        "apiV3Key", apiV3Key);
        return new PaymentGatewayPort.Settings(values, secrets);
    }

    private static String pem(String label, byte[] bytes) {
        String base64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----";
    }

    private record Fixture(String body, Map<String, String> headers) {}

    private record Transaction(
            String appid,
            String mchid,
            String out_trade_no,
            String transaction_id,
            String trade_type,
            String trade_state,
            String success_time,
            long amount,
            String currency) {
        static Transaction valid() {
            return new Transaction(
                    APP_ID,
                    MERCHANT_ID,
                    MERCHANT_ORDER_NO,
                    TRANSACTION_ID,
                    "NATIVE",
                    "SUCCESS",
                    "2026-10-11T01:59:00+00:00",
                    1234,
                    "CNY");
        }

        Transaction withAppId(String value) {
            return new Transaction(
                    value,
                    mchid,
                    out_trade_no,
                    transaction_id,
                    trade_type,
                    trade_state,
                    success_time,
                    amount,
                    currency);
        }

        Transaction withMerchantId(String value) {
            return new Transaction(
                    appid,
                    value,
                    out_trade_no,
                    transaction_id,
                    trade_type,
                    trade_state,
                    success_time,
                    amount,
                    currency);
        }

        Transaction withOrderNo(String value) {
            return new Transaction(
                    appid,
                    mchid,
                    value,
                    transaction_id,
                    trade_type,
                    trade_state,
                    success_time,
                    amount,
                    currency);
        }

        Transaction withAmount(long value) {
            return new Transaction(
                    appid,
                    mchid,
                    out_trade_no,
                    transaction_id,
                    trade_type,
                    trade_state,
                    success_time,
                    value,
                    currency);
        }

        Transaction withCurrency(String value) {
            return new Transaction(
                    appid,
                    mchid,
                    out_trade_no,
                    transaction_id,
                    trade_type,
                    trade_state,
                    success_time,
                    amount,
                    value);
        }

        Transaction withTradeType(String value) {
            return new Transaction(
                    appid,
                    mchid,
                    out_trade_no,
                    transaction_id,
                    value,
                    trade_state,
                    success_time,
                    amount,
                    currency);
        }

        Transaction withTradeState(String value) {
            return new Transaction(
                    appid,
                    mchid,
                    out_trade_no,
                    transaction_id,
                    trade_type,
                    value,
                    success_time,
                    amount,
                    currency);
        }

        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("appid", appid);
            result.put("mchid", mchid);
            result.put("out_trade_no", out_trade_no);
            result.put("transaction_id", transaction_id);
            result.put("trade_type", trade_type);
            result.put("trade_state", trade_state);
            result.put("success_time", success_time);
            result.put("amount", Map.of("total", amount, "currency", currency));
            return result;
        }
    }
}
