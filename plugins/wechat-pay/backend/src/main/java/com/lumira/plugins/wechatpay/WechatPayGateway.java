package com.lumira.plugins.wechatpay;

import com.lumira.plugin.sdk.common.PaymentCrypto;
import com.lumira.plugin.sdk.common.PaymentGatewayPort;
import com.lumira.plugin.sdk.common.PaymentHttpClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * WeChat Pay APIv3 Native adapter using the merchant API certificate and platform public key modes.
 */
public final class WechatPayGateway implements PaymentGatewayPort, AutoCloseable {
    private static final String PROVIDER_ID = "wechat-pay";
    private static final String CURRENCY = "CNY";
    private static final String NATIVE_PATH = "/v3/pay/transactions/native";
    private static final long QR_TTL_SECONDS = 15 * 60;
    private static final long MAX_AMOUNT_FEN = Integer.MAX_VALUE;
    private static final long SIGNATURE_SKEW_SECONDS = 300;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_CALLBACK_BYTES = 128 * 1024;
    private static final String API_ORIGIN = "https://api.mch.weixin.qq.com";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentHttpClient http;
    private final Clock clock;

    public WechatPayGateway() {
        this(
                new PaymentHttpClient(
                        URI.create(API_ORIGIN),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(8),
                        MAX_RESPONSE_BYTES),
                Clock.systemUTC());
    }

    WechatPayGateway(PaymentHttpClient http, Clock clock) {
        this.http = java.util.Objects.requireNonNull(http, "http");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void close() {
        http.close();
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public Set<String> secretFields() {
        return Set.of(
                WechatPaySettings.MERCHANT_PRIVATE_KEY,
                WechatPaySettings.WECHAT_PUBLIC_KEY,
                WechatPaySettings.API_V3_KEY);
    }

    @Override
    public ValidationResult validate(Settings settings) {
        return WechatPaySettings.validate(settings);
    }

    @Override
    public PrecreateResult precreate(Settings settings, OrderRequest request) {
        WechatPaySettings.Configuration config = WechatPaySettings.require(settings);
        validateOrderRequest(request);
        String notifyUrl = WechatPaySettings.validateNotifyUrl(request.notifyUrl());
        if (!config.notifyUrl().equals(notifyUrl))
            throw new IllegalArgumentException(
                    "WeChat Pay callback URL does not match the configured URL");

        Instant expiresAt = clock.instant().plusSeconds(QR_TTL_SECONDS);
        Map<String, Object> amount = Map.of("total", request.amountFen(), "currency", CURRENCY);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", config.appId());
        body.put("mchid", config.merchantId());
        body.put("description", request.subject());
        body.put("out_trade_no", request.merchantOrderNo());
        body.put("time_expire", DateTimeFormatter.ISO_INSTANT.format(expiresAt));
        body.put("notify_url", notifyUrl);
        body.put("amount", amount);

        JsonNode response = call(config, "POST", NATIVE_PATH, WechatPayJson.write(body), 200);
        requireOptionalText(response, "appid", config.appId());
        requireOptionalText(response, "mchid", config.merchantId());
        requireOptionalText(response, "out_trade_no", request.merchantOrderNo());
        String codeUrl = WechatPayJson.requiredText(response, "code_url");
        validateCodeUrl(codeUrl);
        return new PrecreateResult(null, codeUrl, expiresAt);
    }

    @Override
    public TradeResult query(Settings settings, String merchantOrderNo) {
        WechatPaySettings.Configuration config = WechatPaySettings.require(settings);
        requireOrderNo(merchantOrderNo);
        String target =
                "/v3/pay/transactions/out-trade-no/"
                        + merchantOrderNo
                        + "?mchid="
                        + config.merchantId();
        JsonNode response = call(config, "GET", target, "", 200);
        requireTradeIdentity(response, config, merchantOrderNo);
        String providerTradeNo = WechatPayJson.text(response, "transaction_id");
        if (providerTradeNo != null && !validTradeNo(providerTradeNo))
            throw new IllegalArgumentException("WeChat Pay transaction ID is invalid");
        String tradeState = WechatPayJson.requiredText(response, "trade_state");
        requireOptionalText(response, "trade_type", "NATIVE");
        long totalFen = 0;
        String currency = CURRENCY;
        JsonNode amount = response.get("amount");
        if (amount != null && !amount.isNull()) {
            if (!amount.isObject())
                throw new IllegalArgumentException("WeChat Pay query amount is invalid");
            String responseCurrency = WechatPayJson.text(amount, "currency");
            if (responseCurrency != null && !CURRENCY.equals(responseCurrency))
                throw new IllegalArgumentException("WeChat Pay query currency is invalid");
            JsonNode total = amount.get("total");
            if (total != null && !total.isNull()) {
                totalFen = WechatPayJson.requiredLong(amount, "total");
                if (totalFen < 1 || totalFen > MAX_AMOUNT_FEN)
                    throw new IllegalArgumentException("WeChat Pay query amount is invalid");
            }
        }
        return switch (tradeState) {
            case "SUCCESS" -> {
                if (totalFen == 0
                        || !CURRENCY.equals(WechatPayJson.requiredText(amount, "currency")))
                    throw new IllegalArgumentException(
                            "WeChat Pay successful query amount is missing or invalid");
                if (!validTradeNo(providerTradeNo))
                    throw new IllegalArgumentException("WeChat Pay transaction ID is invalid");
                Instant paidAt = requiredProviderTime(response, "success_time");
                yield new TradeResult(
                        TradeState.PAID, providerTradeNo, totalFen, currency, paidAt, tradeState);
            }
            case "NOTPAY", "USERPAYING" ->
                    new TradeResult(
                            TradeState.PENDING, providerTradeNo, 0, currency, null, tradeState);
            case "CLOSED", "REVOKED" ->
                    new TradeResult(
                            TradeState.CLOSED, providerTradeNo, 0, currency, null, tradeState);
            case "PAYERROR" ->
                    new TradeResult(
                            TradeState.FAILED, providerTradeNo, 0, currency, null, tradeState);
            default -> throw new IllegalArgumentException("WeChat Pay trade state is unsupported");
        };
    }

    @Override
    public void close(Settings settings, String merchantOrderNo) {
        WechatPaySettings.Configuration config = WechatPaySettings.require(settings);
        requireOrderNo(merchantOrderNo);
        String path = "/v3/pay/transactions/out-trade-no/" + merchantOrderNo + "/close";
        String body = WechatPayJson.write(Map.of("mchid", config.merchantId()));
        call(config, "POST", path, body, 204);
    }

    @Override
    public RefundResult refund(Settings settings, RefundRequest request) {
        WechatPaySettings.Configuration config = WechatPaySettings.require(settings);
        validateRefundRequest(request);
        TradeResult trade = query(settings, request.merchantOrderNo());
        if (trade.state() != TradeState.PAID
                || trade.amountFen() != request.amountFen()
                || !CURRENCY.equals(trade.currency()))
            throw new IllegalArgumentException(
                    "WeChat Pay full refund requires a paid order with the exact amount");

        Map<String, Object> amount =
                Map.of(
                        "refund",
                        request.amountFen(),
                        "total",
                        request.amountFen(),
                        "currency",
                        CURRENCY);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("out_trade_no", request.merchantOrderNo());
        body.put("out_refund_no", request.outRefundNo());
        body.put("reason", normalizedReason(request.reason()));
        body.put("amount", amount);
        JsonNode response =
                call(config, "POST", "/v3/refund/domestic/refunds", WechatPayJson.write(body), 200);
        return parseRefundResult(response, config, request, trade.providerTradeNo());
    }

    @Override
    public RefundResult queryRefund(Settings settings, RefundRequest request) {
        WechatPaySettings.Configuration config = WechatPaySettings.require(settings);
        validateRefundRequest(request);
        JsonNode response =
                call(
                        config,
                        "GET",
                        "/v3/refund/domestic/refunds/" + request.outRefundNo(),
                        "",
                        200);
        return parseRefundResult(response, config, request, null);
    }

    @Override
    public Notification verifyCallback(
            Settings settings, Map<String, String> headers, String rawBody) {
        WechatPaySettings.Configuration config = WechatPaySettings.require(settings);
        if (rawBody == null || headers == null || headers.size() > 64)
            throw new IllegalArgumentException("WeChat Pay notification is invalid");
        byte[] bodyBytes = rawBody.getBytes(StandardCharsets.UTF_8);
        if (bodyBytes.length == 0 || bodyBytes.length > MAX_CALLBACK_BYTES)
            throw new IllegalArgumentException("WeChat Pay notification size is invalid");
        verifyMessageSignature(config, normalizeHeaders(headers), rawBody);

        JsonNode envelope = WechatPayJson.parseObject(bodyBytes);
        String eventId = WechatPayJson.requiredText(envelope, "id");
        if (eventId.length() > 128)
            throw new IllegalArgumentException("WeChat Pay notification ID is invalid");
        requiredProviderTime(envelope, "create_time");
        String eventType = WechatPayJson.requiredText(envelope, "event_type");
        if (!eventType.equals("TRANSACTION.SUCCESS"))
            throw new IllegalArgumentException("WeChat Pay notification event is unsupported");
        requireExactText(envelope, "resource_type", "encrypt-resource");
        JsonNode resource = requiredObject(envelope, "resource");
        if (!"transaction".equals(WechatPayJson.requiredText(resource, "original_type"))
                || !"AEAD_AES_256_GCM".equals(WechatPayJson.requiredText(resource, "algorithm")))
            throw new IllegalArgumentException("WeChat Pay notification resource is unsupported");
        String nonce = WechatPayJson.requiredText(resource, "nonce");
        String associatedData = WechatPayJson.text(resource, "associated_data");
        String ciphertext = WechatPayJson.requiredText(resource, "ciphertext", MAX_CALLBACK_BYTES);
        byte[] plaintext =
                PaymentCrypto.decryptAes256Gcm(
                        config.apiV3Key(), nonce, associatedData, ciphertext);
        try {
            JsonNode transaction = WechatPayJson.parseObject(plaintext);
            validateCallbackTransaction(transaction, config);
            JsonNode amount = requiredObject(transaction, "amount");
            long amountFen = WechatPayJson.requiredLong(amount, "total");
            if (amountFen < 1
                    || amountFen > MAX_AMOUNT_FEN
                    || !CURRENCY.equals(WechatPayJson.requiredText(amount, "currency")))
                throw new IllegalArgumentException(
                        "WeChat Pay notification amount or currency is invalid");
            Instant occurredAt = requiredProviderTime(transaction, "success_time");
            if (occurredAt.isAfter(clock.instant().plusSeconds(SIGNATURE_SKEW_SECONDS)))
                throw new IllegalArgumentException("WeChat Pay notification time is in the future");
            return new Notification(
                    WechatPayJson.requiredText(transaction, "out_trade_no"),
                    WechatPayJson.requiredText(transaction, "transaction_id"),
                    amountFen,
                    CURRENCY,
                    true,
                    eventId,
                    occurredAt);
        } finally {
            java.util.Arrays.fill(plaintext, (byte) 0);
        }
    }

    private JsonNode call(
            WechatPaySettings.Configuration config,
            String method,
            String target,
            String body,
            int expectedStatus) {
        long timestamp = clock.instant().getEpochSecond();
        String nonce = newNonce();
        String authorization = authorization(config, method, target, timestamp, nonce, body);
        Map<String, String> headers =
                Map.of(
                        "Authorization",
                        authorization,
                        "Wechatpay-Serial",
                        config.wechatPayPublicKeyId(),
                        "Content-Type",
                        "application/json; charset=utf-8");
        PaymentHttpClient.Response response =
                http.send(method, target, headers, body.getBytes(StandardCharsets.UTF_8));
        String responseBody = decodeUtf8(response.body());
        verifyMessageSignature(config, normalizeResponseHeaders(response.headers()), responseBody);
        if (response.statusCode() != expectedStatus)
            throw new IllegalStateException("WeChat Pay API returned an unexpected HTTP status");
        if (expectedStatus == 204) {
            if (!responseBody.isEmpty())
                throw new IllegalArgumentException("WeChat Pay close response must be empty");
            return null;
        }
        return WechatPayJson.parseObject(response.body());
    }

    private String authorization(
            WechatPaySettings.Configuration config,
            String method,
            String target,
            long timestamp,
            String nonce,
            String body) {
        String canonical =
                method + "\n" + target + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        String signature = PaymentCrypto.signSha256Rsa(config.merchantPrivateKey(), canonical);
        return "WECHATPAY2-SHA256-RSA2048 mchid=\""
                + config.merchantId()
                + "\",nonce_str=\""
                + nonce
                + "\",timestamp=\""
                + timestamp
                + "\",serial_no=\""
                + config.merchantSerialNumber()
                + "\",signature=\""
                + signature
                + "\"";
    }

    private void verifyMessageSignature(
            WechatPaySettings.Configuration config, Map<String, String> headers, String body) {
        String timestamp = requiredHeader(headers, "wechatpay-timestamp");
        String nonce = requiredHeader(headers, "wechatpay-nonce");
        String serial = requiredHeader(headers, "wechatpay-serial");
        String signature = requiredHeader(headers, "wechatpay-signature");
        if (!timestamp.matches("[0-9]{1,12}") || !serial.equals(config.wechatPayPublicKeyId()))
            throw new IllegalArgumentException("WeChat Pay signature metadata is invalid");
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("WeChat Pay signature timestamp is invalid");
        }
        long now = clock.instant().getEpochSecond();
        if (epochSeconds < now - SIGNATURE_SKEW_SECONDS
                || epochSeconds > now + SIGNATURE_SKEW_SECONDS)
            throw new IllegalArgumentException("WeChat Pay signature timestamp is stale");
        if (nonce.length() > 128
                || nonce.isBlank()
                || containsControl(nonce)
                || signature.length() > 8192
                || signature.isBlank())
            throw new IllegalArgumentException("WeChat Pay signature metadata is invalid");
        String message = timestamp + "\n" + nonce + "\n" + body + "\n";
        if (!PaymentCrypto.verifySha256Rsa(config.wechatPayPublicKey(), message, signature))
            throw new IllegalArgumentException("WeChat Pay signature verification failed");
    }

    private RefundResult parseRefundResult(
            JsonNode response,
            WechatPaySettings.Configuration config,
            RefundRequest request,
            String expectedTradeNo) {
        requireOptionalText(response, "mchid", config.merchantId());
        requireExactText(response, "out_trade_no", request.merchantOrderNo());
        requireExactText(response, "out_refund_no", request.outRefundNo());
        String transactionId = WechatPayJson.requiredText(response, "transaction_id");
        if (!validTradeNo(transactionId))
            throw new IllegalArgumentException("WeChat Pay transaction ID is invalid");
        if (expectedTradeNo != null && !expectedTradeNo.equals(transactionId))
            throw new IllegalArgumentException(
                    "WeChat Pay refund transaction does not match the paid order");
        String providerRefundNo = WechatPayJson.requiredText(response, "refund_id");
        if (!validRefundNo(providerRefundNo))
            throw new IllegalArgumentException("WeChat Pay refund ID is invalid");

        JsonNode amount = requiredObject(response, "amount");
        long refundFen = WechatPayJson.requiredLong(amount, "refund");
        long totalFen = WechatPayJson.requiredLong(amount, "total");
        String currency = WechatPayJson.requiredText(amount, "currency");
        if (!CURRENCY.equals(currency)
                || refundFen != request.amountFen()
                || totalFen != request.amountFen())
            throw new IllegalArgumentException(
                    "WeChat Pay refund amount or currency does not match the full order amount");

        String status = WechatPayJson.requiredText(response, "status");
        return switch (status) {
            case "SUCCESS" ->
                    new RefundResult(
                            RefundState.SUCCESS,
                            providerRefundNo,
                            refundFen,
                            requiredProviderTime(response, "success_time"),
                            status);
            // 异常退款仍可继续成功，保留原退款号等待查询，不允许盲目重发。
            // Abnormal refunds may later succeed; retain the original refund ID for reconciliation.
            case "PROCESSING", "ABNORMAL" ->
                    new RefundResult(RefundState.PENDING, providerRefundNo, 0, null, status);
            case "CLOSED" ->
                    new RefundResult(RefundState.FAILED, providerRefundNo, 0, null, status);
            default -> throw new IllegalArgumentException("WeChat Pay refund state is unsupported");
        };
    }

    private void validateCallbackTransaction(
            JsonNode transaction, WechatPaySettings.Configuration config) {
        requireExactText(transaction, "appid", config.appId());
        requireExactText(transaction, "mchid", config.merchantId());
        requireExactText(transaction, "trade_state", "SUCCESS");
        requireExactText(transaction, "trade_type", "NATIVE");
        requireOrderNo(WechatPayJson.requiredText(transaction, "out_trade_no"));
        if (!validTradeNo(WechatPayJson.requiredText(transaction, "transaction_id")))
            throw new IllegalArgumentException("WeChat Pay transaction ID is invalid");
    }

    private static void requireTradeIdentity(
            JsonNode response, WechatPaySettings.Configuration config, String orderNo) {
        requireExactText(response, "appid", config.appId());
        requireExactText(response, "mchid", config.merchantId());
        requireExactText(response, "out_trade_no", orderNo);
    }

    private static void validateOrderRequest(OrderRequest request) {
        if (request == null)
            throw new IllegalArgumentException("WeChat Pay order request is required");
        requireOrderNo(request.merchantOrderNo());
        requireAmount(request.amountFen());
        if (request.subject() == null
                || request.subject().isBlank()
                || request.subject().codePointCount(0, request.subject().length()) > 127
                || request.subject().getBytes(StandardCharsets.UTF_8).length > 127
                || containsControl(request.subject()))
            throw new IllegalArgumentException("WeChat Pay order description is invalid");
    }

    private static void validateRefundRequest(RefundRequest request) {
        if (request == null)
            throw new IllegalArgumentException("WeChat Pay refund request is required");
        requireOrderNo(request.merchantOrderNo());
        if (request.outRefundNo() == null || !request.outRefundNo().matches("[A-Za-z0-9_-]{6,32}"))
            throw new IllegalArgumentException("WeChat Pay refund number is invalid");
        requireAmount(request.amountFen());
        normalizedReason(request.reason());
    }

    private static String normalizedReason(String value) {
        String reason = value == null ? "" : value.strip();
        if (reason.getBytes(StandardCharsets.UTF_8).length > 80 || containsControl(reason))
            throw new IllegalArgumentException("WeChat Pay refund reason is invalid");
        return reason;
    }

    private static void requireAmount(long amountFen) {
        if (amountFen < 1 || amountFen > MAX_AMOUNT_FEN)
            throw new IllegalArgumentException(
                    "WeChat Pay amount must be a positive 32-bit fen value");
    }

    private static void requireOrderNo(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_*\\-]{6,32}"))
            throw new IllegalArgumentException("WeChat Pay merchant order number is invalid");
    }

    private static boolean validTradeNo(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,32}");
    }

    private static boolean validRefundNo(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,32}");
    }

    private static String requiredHeader(Map<String, String> headers, String name) {
        String value = headers.get(name);
        if (value == null || value.isBlank() || containsControl(value))
            throw new IllegalArgumentException("WeChat Pay signature header is missing or invalid");
        return value;
    }

    private static Map<String, String> normalizeHeaders(Map<String, String> headers) {
        Map<String, String> normalized = new LinkedHashMap<>();
        headers.forEach(
                (name, value) -> {
                    if (name == null
                            || value == null
                            || name.length() > 128
                            || value.length() > 8192
                            || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
                            || containsControl(value))
                        throw new IllegalArgumentException("WeChat Pay headers are invalid");
                    String key = name.toLowerCase(Locale.ROOT);
                    String prior = normalized.putIfAbsent(key, value);
                    // The payment core passes lowercase names; reject conflicting aliases
                    // defensively.
                    if (prior != null && !prior.equals(value))
                        throw new IllegalArgumentException("Duplicate WeChat Pay signature header");
                });
        return Map.copyOf(normalized);
    }

    private static Map<String, String> normalizeResponseHeaders(Map<String, List<String>> headers) {
        Map<String, String> flattened = new LinkedHashMap<>();
        headers.forEach(
                (name, values) -> {
                    if (values == null || values.size() != 1)
                        throw new IllegalArgumentException("Duplicate WeChat Pay response header");
                    flattened.put(name, values.getFirst());
                });
        return normalizeHeaders(flattened);
    }

    private static JsonNode requiredObject(JsonNode parent, String name) {
        JsonNode value = parent == null ? null : parent.get(name);
        if (value == null || !value.isObject())
            throw new IllegalArgumentException("WeChat Pay object field is missing: " + name);
        return value;
    }

    private static void requireExactText(JsonNode object, String field, String expected) {
        if (!expected.equals(WechatPayJson.requiredText(object, field)))
            throw new IllegalArgumentException("WeChat Pay identity does not match: " + field);
    }

    private static void requireOptionalText(JsonNode object, String field, String expected) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null) return;
        if (!value.isTextual() || !expected.equals(value.asText()))
            throw new IllegalArgumentException("WeChat Pay identity does not match: " + field);
    }

    private Instant requiredProviderTime(JsonNode object, String field) {
        String value = WechatPayJson.requiredText(object, field);
        try {
            Instant result =
                    OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            if (result.isAfter(clock.instant().plusSeconds(SIGNATURE_SKEW_SECONDS)))
                throw new IllegalArgumentException("Provider time is in the future");
            return result;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("WeChat Pay timestamp is invalid: " + field);
        }
    }

    static void validateCodeUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("WeChat Pay Native code URL is invalid");
        }
        if (!"weixin".equalsIgnoreCase(uri.getScheme())
                || !"wxpay".equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || !("/bizpayurl".equals(uri.getRawPath())
                        || "/bizpayurl/up".equals(uri.getRawPath()))
                || uri.getFragment() != null
                || uri.getRawQuery() == null
                || uri.toString().length() > 512)
            throw new IllegalArgumentException("WeChat Pay Native code URL is invalid");
    }

    private static String newNonce() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException("WeChat Pay response is not valid UTF-8");
        }
    }

    private static boolean containsControl(String value) {
        return value != null && value.codePoints().anyMatch(Character::isISOControl);
    }
}
