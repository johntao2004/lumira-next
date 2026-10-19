package com.lumira.plugins.wechatpay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.lumira.plugin.sdk.common.PaymentCrypto;
import com.lumira.plugin.sdk.common.PaymentGatewayPort;
import com.lumira.plugin.sdk.common.PaymentHttpClient;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import tools.jackson.databind.json.JsonMapper;

/**
 * 仅回环 HTTPS 的退款协议夹具；独立商户/平台密钥，不调用真实支付。 Loopback-only HTTPS refund contract fixture with independent
 * merchant/platform keys.
 */
@Isolated("Briefly sets the default TLS context while constructing the real HTTP client")
class WechatPayRefundHttpContractTest {
    private static final String APP_ID = "wx-fixture-app-123";
    private static final String MERCHANT_ID = "1900000109";
    private static final String MERCHANT_SERIAL = "0123456789ABCDEF";
    private static final String PUBLIC_KEY_ID = "PUB_KEY_ID_FIXTURE_0001";
    private static final String ORDER_NO = "LM0123456789ABCDEF0123456789ABCD";
    private static final String REFUND_NO = "LR0123456789ABCDEF0123456789ABCD";
    private static final String TRANSACTION_ID = "4200000000202610111234567890";
    private static final String PROVIDER_REFUND_NO = "50000000382026101109732678859";
    private static final Instant NOW = Instant.parse("2026-10-11T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PATH = "/v3/refund/domestic/refunds/" + REFUND_NO;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir static Path directory;
    private static KeyPair merchantKeyPair;
    private static KeyPair platformKeyPair;
    private static SSLContext tls;
    private static PaymentGatewayPort.Settings settings;

    @BeforeAll
    static void certificatesAndIndependentSigningKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        merchantKeyPair = generator.generateKeyPair();
        platformKeyPair = generator.generateKeyPair();
        assertNotEquals(merchantKeyPair.getPublic(), platformKeyPair.getPublic());
        settings =
                new PaymentGatewayPort.Settings(
                        Map.of(
                                "appId",
                                APP_ID,
                                "merchantId",
                                MERCHANT_ID,
                                "merchantSerialNumber",
                                MERCHANT_SERIAL,
                                "wechatPayPublicKeyId",
                                PUBLIC_KEY_ID,
                                "notifyUrl",
                                "https://pay.example.test/api/plugins/payment-core/callbacks/wechat-pay"),
                        Map.of(
                                "merchantPrivateKeyPem",
                                pem("PRIVATE KEY", merchantKeyPair.getPrivate().getEncoded()),
                                "wechatPayPublicKeyPem",
                                pem("PUBLIC KEY", platformKeyPair.getPublic().getEncoded()),
                                "apiV3Key",
                                "0123456789abcdef0123456789abcdef"));
        assertTrue(WechatPaySettings.validate(settings).valid());
        Path store = directory.resolve("localhost.p12");
        Process keytool =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "keytool")
                                        .toString(),
                                "-genkeypair",
                                "-alias",
                                "fixture",
                                "-keyalg",
                                "RSA",
                                "-keysize",
                                "2048",
                                "-validity",
                                "2",
                                "-dname",
                                "CN=localhost",
                                "-ext",
                                "SAN=ip:127.0.0.1,dns:localhost",
                                "-storetype",
                                "PKCS12",
                                "-keystore",
                                store.toString(),
                                "-storepass",
                                "local-fixture-only",
                                "-keypass",
                                "local-fixture-only",
                                "-noprompt")
                        .redirectErrorStream(true)
                        .redirectOutput(directory.resolve("keytool.log").toFile())
                        .start();
        if (!keytool.waitFor(20, TimeUnit.SECONDS)) {
            keytool.destroyForcibly();
            fail("Loopback certificate generation timed out");
        }
        assertEquals(0, keytool.exitValue());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            keys.load(input, "local-fixture-only".toCharArray());
        }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, "local-fixture-only".toCharArray());
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        tls = SSLContext.getInstance("TLS");
        tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
    }

    @Test
    void abnormalRemainsPendingAndTheSameRefundCanLaterSucceed() throws Exception {
        try (Fixture fixture = new Fixture("ABNORMAL", false)) {
            var abnormal = fixture.gateway.queryRefund(settings, request());
            assertEquals(PaymentGatewayPort.RefundState.PENDING, abnormal.state());
            assertEquals(PROVIDER_REFUND_NO, abnormal.providerRefundNo());
            assertEquals(0, abnormal.amountFen());
            assertNull(abnormal.completedAt());
            assertEquals("ABNORMAL", abnormal.message());

            fixture.status.set("SUCCESS");
            var completed = fixture.gateway.queryRefund(settings, request());
            assertEquals(PaymentGatewayPort.RefundState.SUCCESS, completed.state());
            assertEquals(PROVIDER_REFUND_NO, completed.providerRefundNo());
            assertEquals(1234, completed.amountFen());
            assertEquals(NOW.minusSeconds(30), completed.completedAt());
            fixture.assertRequests(2);
        }
    }

    @Test
    void closedRefundIsAFinalFailure() throws Exception {
        try (Fixture fixture = new Fixture("CLOSED", false)) {
            var closed = fixture.gateway.queryRefund(settings, request());
            assertEquals(PaymentGatewayPort.RefundState.FAILED, closed.state());
            assertEquals(PROVIDER_REFUND_NO, closed.providerRefundNo());
            assertEquals(0, closed.amountFen());
            assertNull(closed.completedAt());
            fixture.assertRequests(1);
        }
    }

    @Test
    void rejectsTamperedPlatformSignatureBeforeReturningSuccess() throws Exception {
        try (Fixture fixture = new Fixture("SUCCESS", true)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.gateway.queryRefund(settings, request()));
            fixture.assertRequests(1);
        }
    }

    private static PaymentGatewayPort.RefundRequest request() {
        return new PaymentGatewayPort.RefundRequest(
                ORDER_NO, REFUND_NO, 1234, "Synthetic test refund");
    }

    private static String pem(String label, byte[] bytes) {
        return "-----BEGIN "
                + label
                + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
                + "\n-----END "
                + label
                + "-----";
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicReference<String> status;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        final HttpsServer server;
        final WechatPayGateway gateway;

        Fixture(String initialStatus, boolean corruptSignature) throws Exception {
            status = new AtomicReference<>(initialStatus);
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(tls));
            server.createContext(
                    "/",
                    exchange -> {
                        try {
                            requests.incrementAndGet();
                            assertEquals("GET", exchange.getRequestMethod());
                            assertEquals(PATH, exchange.getRequestURI().toString());
                            assertEquals(0, exchange.getRequestBody().readAllBytes().length);
                            assertEquals(
                                    "application/json",
                                    exchange.getRequestHeaders().getFirst("Accept"));
                            assertEquals(
                                    PUBLIC_KEY_ID,
                                    exchange.getRequestHeaders().getFirst("Wechatpay-Serial"));
                            String auth = exchange.getRequestHeaders().getFirst("Authorization");
                            assertNotNull(auth);
                            assertTrue(auth.startsWith("WECHATPAY2-SHA256-RSA2048 "));
                            Map<String, String> authFields = new LinkedHashMap<>();
                            var matcher = Pattern.compile("([a-z_]+)=\"([^\"]*)\"").matcher(auth);
                            while (matcher.find())
                                authFields.put(matcher.group(1), matcher.group(2));
                            assertEquals(MERCHANT_ID, authFields.get("mchid"));
                            assertEquals(MERCHANT_SERIAL, authFields.get("serial_no"));
                            assertEquals(
                                    Long.toString(NOW.getEpochSecond()),
                                    authFields.get("timestamp"));
                            String canonical =
                                    "GET\n"
                                            + PATH
                                            + "\n"
                                            + authFields.get("timestamp")
                                            + "\n"
                                            + authFields.get("nonce_str")
                                            + "\n\n";
                            assertTrue(
                                    PaymentCrypto.verifySha256Rsa(
                                            merchantKeyPair.getPublic(),
                                            canonical,
                                            authFields.get("signature")),
                                    "Request must be signed by the merchant key");

                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("mchid", MERCHANT_ID);
                            payload.put("out_trade_no", ORDER_NO);
                            payload.put("out_refund_no", REFUND_NO);
                            payload.put("transaction_id", TRANSACTION_ID);
                            payload.put("refund_id", PROVIDER_REFUND_NO);
                            payload.put("status", status.get());
                            payload.put(
                                    "amount",
                                    Map.of("refund", 1234, "total", 1234, "currency", "CNY"));
                            if ("SUCCESS".equals(status.get()))
                                payload.put("success_time", NOW.minusSeconds(30).toString());
                            String body = JSON.writeValueAsString(payload);
                            String timestamp = Long.toString(NOW.getEpochSecond());
                            String nonce = "signed-refund-response";
                            String signature =
                                    PaymentCrypto.signSha256Rsa(
                                            platformKeyPair.getPrivate(),
                                            timestamp + "\n" + nonce + "\n" + body + "\n");
                            if (corruptSignature) {
                                byte[] signatureBytes = Base64.getDecoder().decode(signature);
                                signatureBytes[0] ^= 1;
                                signature = Base64.getEncoder().encodeToString(signatureBytes);
                            }
                            exchange.getResponseHeaders()
                                    .set("Content-Type", "application/json; charset=utf-8");
                            exchange.getResponseHeaders().set("Wechatpay-Timestamp", timestamp);
                            exchange.getResponseHeaders().set("Wechatpay-Nonce", nonce);
                            exchange.getResponseHeaders().set("Wechatpay-Serial", PUBLIC_KEY_ID);
                            exchange.getResponseHeaders().set("Wechatpay-Signature", signature);
                            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(200, bytes.length);
                            exchange.getResponseBody().write(bytes);
                        } catch (Throwable error) {
                            handlerFailure.compareAndSet(null, error);
                            exchange.sendResponseHeaders(500, -1);
                        } finally {
                            exchange.close();
                        }
                    });
            server.start();
            SSLContext prior = SSLContext.getDefault();
            try {
                // 只在客户端构造期间信任临时本地证书；仍执行主机名验证。
                // Trust the temporary local certificate only during client construction; hostname
                // checks stay on.
                SSLContext.setDefault(tls);
                gateway =
                        new WechatPayGateway(
                                new PaymentHttpClient(
                                        URI.create(
                                                "https://127.0.0.1:"
                                                        + server.getAddress().getPort()),
                                        Duration.ofSeconds(2),
                                        Duration.ofSeconds(3),
                                        16384),
                                CLOCK);
            } finally {
                SSLContext.setDefault(prior);
            }
        }

        void assertRequests(int expected) {
            assertNull(handlerFailure.get(), () -> "Fixture failed: " + handlerFailure.get());
            assertEquals(expected, requests.get());
        }

        @Override
        public void close() {
            gateway.close();
            server.stop(0);
        }
    }
}
