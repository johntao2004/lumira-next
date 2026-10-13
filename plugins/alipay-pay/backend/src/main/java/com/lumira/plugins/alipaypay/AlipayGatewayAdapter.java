package com.lumira.plugins.alipaypay;

import com.lumira.plugin.sdk.common.PaymentGatewayPort;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Alipay Face-to-Face QR implementation of the platform-owned payment gateway SPI. */
@Service
public final class AlipayGatewayAdapter implements PaymentGatewayPort {
    private static final String PROVIDER_ID = "alipay-pay";
    private static final String CURRENCY = "CNY";
    private static final long QR_LIFETIME_SECONDS = 15 * 60;
    private static final DateTimeFormatter ALIPAY_TIME =
            new DateTimeFormatterBuilder()
                    .appendPattern("uuuu-MM-dd HH:mm:ss")
                    .toFormatter(Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);
    private static final ZoneId ALIPAY_ZONE = ZoneId.of("Asia/Shanghai");

    private final AlipayGatewayInvoker client;

    public AlipayGatewayAdapter() {
        this(new AlipayGatewayClient());
    }

    AlipayGatewayAdapter(AlipayGatewayInvoker client) {
        this.client = client;
    }

    @PreDestroy
    public void close() {
        client.close();
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public Set<String> secretFields() {
        return Set.of(AlipaySettings.APP_PRIVATE_KEY, AlipaySettings.ALIPAY_PUBLIC_KEY);
    }

    @Override
    public ValidationResult validate(Settings settings) {
        return AlipaySettings.validate(settings);
    }

    @Override
    public PrecreateResult precreate(Settings settings, OrderRequest request) {
        AlipaySettings.Configuration config = AlipaySettings.require(settings);
        validateOrderRequest(request);
        Map<String, Object> content =
                AlipayGatewayClient.content(
                        "out_trade_no", request.merchantOrderNo(),
                        "seller_id", config.sellerId(),
                        "total_amount", AlipayAmount.toYuan(request.amountFen()),
                        "subject", request.subject(),
                        "timeout_express", "15m");
        JsonNode response =
                client.invoke(config, "alipay.trade.precreate", content, request.notifyUrl());
        AlipayJson.requireSuccess(response);
        requireExactMatch("out_trade_no", request.merchantOrderNo(), response);
        requireOptionalIdentity(config, response, request.merchantOrderNo(), request.amountFen());
        String codeUrl = AlipayJson.requiredText(response, "qr_code");
        validateCodeUrl(codeUrl);
        return new PrecreateResult(null, codeUrl, Instant.now().plusSeconds(QR_LIFETIME_SECONDS));
    }

    @Override
    public TradeResult query(Settings settings, String merchantOrderNo) {
        AlipaySettings.Configuration config = AlipaySettings.require(settings);
        requireOrderNo(merchantOrderNo);
        JsonNode response =
                client.invoke(
                        config,
                        "alipay.trade.query",
                        AlipayGatewayClient.content("out_trade_no", merchantOrderNo),
                        null);
        if (!"10000".equals(AlipayJson.text(response, "code"))) {
            if ("ACQ.TRADE_NOT_EXIST".equals(AlipayJson.text(response, "sub_code")))
                return new TradeResult(TradeState.FAILED, null, 0, CURRENCY, null, "交易不存在");
            AlipayJson.requireSuccess(response);
        }
        requireExactMatch("out_trade_no", merchantOrderNo, response);
        requireOptionalIdentity(config, response, merchantOrderNo, null);
        long amountFen = AlipayAmount.toFen(AlipayJson.requiredText(response, "total_amount"));
        if (amountFen <= 0) throw new IllegalArgumentException("Alipay query amount is invalid");
        String tradeNo = AlipayJson.requiredText(response, "trade_no");
        String status = AlipayJson.requiredText(response, "trade_status");
        TradeState state =
                switch (status) {
                    case "WAIT_BUYER_PAY" -> TradeState.PENDING;
                    case "TRADE_SUCCESS", "TRADE_FINISHED" -> TradeState.PAID;
                    case "TRADE_CLOSED" -> TradeState.CLOSED;
                    default ->
                            throw new IllegalArgumentException(
                                    "Alipay query trade status is unsupported");
                };
        String currency = responseCurrency(response);
        Instant paidAt = parseOptionalTime(AlipayJson.text(response, "send_pay_date"));
        return new TradeResult(
                state, tradeNo, state == TradeState.PAID ? amountFen : 0, currency, paidAt, status);
    }

    @Override
    public void close(Settings settings, String merchantOrderNo) {
        AlipaySettings.Configuration config = AlipaySettings.require(settings);
        requireOrderNo(merchantOrderNo);
        JsonNode response =
                client.invoke(
                        config,
                        "alipay.trade.close",
                        AlipayGatewayClient.content("out_trade_no", merchantOrderNo),
                        null);
        AlipayJson.requireSuccess(response);
        requireExactMatch("out_trade_no", merchantOrderNo, response);
        requireOptionalIdentity(config, response, merchantOrderNo, null);
    }

    @Override
    public RefundResult refund(Settings settings, RefundRequest request) {
        AlipaySettings.Configuration config = AlipaySettings.require(settings);
        validateRefundRequest(request);
        JsonNode response =
                client.invoke(
                        config,
                        "alipay.trade.refund",
                        AlipayGatewayClient.content(
                                "out_trade_no", request.merchantOrderNo(),
                                "refund_amount", AlipayAmount.toYuan(request.amountFen()),
                                "out_request_no", request.outRefundNo(),
                                "refund_reason", limit(request.reason(), 256)),
                        null);
        AlipayJson.requireSuccess(response);
        requireExactMatch("out_trade_no", request.merchantOrderNo(), response);
        requireOptionalIdentity(config, response, request.merchantOrderNo(), request.amountFen());
        long refundedFen = AlipayAmount.toFen(AlipayJson.requiredText(response, "refund_fee"));
        if (refundedFen != request.amountFen())
            throw new IllegalArgumentException(
                    "Alipay refund amount does not match the requested full refund");
        return new RefundResult(
                RefundState.SUCCESS,
                request.outRefundNo(),
                refundedFen,
                parseOptionalTime(AlipayJson.text(response, "gmt_refund_pay")),
                "退款已受理");
    }

    @Override
    public RefundResult queryRefund(Settings settings, RefundRequest request) {
        AlipaySettings.Configuration config = AlipaySettings.require(settings);
        validateRefundRequest(request);
        JsonNode response =
                client.invoke(
                        config,
                        "alipay.trade.fastpay.refund.query",
                        AlipayGatewayClient.content(
                                "out_trade_no", request.merchantOrderNo(),
                                "out_request_no", request.outRefundNo()),
                        null);
        AlipayJson.requireSuccess(response);
        requireExactMatch("out_trade_no", request.merchantOrderNo(), response);
        requireMatchIfPresent("out_request_no", request.outRefundNo(), response);
        requireOptionalIdentity(config, response, request.merchantOrderNo(), request.amountFen());
        String status = AlipayJson.text(response, "refund_status");
        String refundAmount = AlipayJson.text(response, "refund_amount");
        long amountFen = refundAmount == null ? 0 : AlipayAmount.toFen(refundAmount);
        if ("REFUND_SUCCESS".equals(status)) {
            if (amountFen != request.amountFen())
                throw new IllegalArgumentException(
                        "Alipay refund query amount does not match the requested full refund");
            return new RefundResult(
                    RefundState.SUCCESS,
                    request.outRefundNo(),
                    amountFen,
                    parseOptionalTime(AlipayJson.text(response, "gmt_refund_pay")),
                    "退款成功");
        }
        if (amountFen != 0 && amountFen != request.amountFen())
            throw new IllegalArgumentException(
                    "Alipay pending refund amount does not match the requested full refund");
        return new RefundResult(RefundState.PENDING, request.outRefundNo(), 0, null, "退款尚未确认成功");
    }

    @Override
    public Notification verifyCallback(
            Settings settings, Map<String, String> headers, String rawBody) {
        return AlipayCallback.verify(settings, headers, rawBody);
    }

    private static void validateOrderRequest(OrderRequest request) {
        if (request == null) throw new IllegalArgumentException("Alipay order request is required");
        requireOrderNo(request.merchantOrderNo());
        AlipayAmount.toYuan(request.amountFen());
        if (request.subject() == null
                || request.subject().isBlank()
                || request.subject().length() > 256)
            throw new IllegalArgumentException("Alipay order subject is invalid");
        if (request.subject().codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Alipay order subject is invalid");
        AlipayGatewayClient.validateNotifyUrl(request.notifyUrl());
    }

    private static void validateRefundRequest(RefundRequest request) {
        if (request == null)
            throw new IllegalArgumentException("Alipay refund request is required");
        requireOrderNo(request.merchantOrderNo());
        if (request.outRefundNo() == null || !request.outRefundNo().matches("[A-Za-z0-9_]{1,64}"))
            throw new IllegalArgumentException("Alipay refund number is invalid");
        AlipayAmount.toYuan(request.amountFen());
        if (request.reason() != null && request.reason().length() > 1000)
            throw new IllegalArgumentException("Alipay refund reason is too long");
    }

    private static String limit(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.strip();
        if (normalized.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Alipay refund reason is invalid");
        if (normalized.codePointCount(0, normalized.length()) > maxLength)
            return normalized.substring(0, normalized.offsetByCodePoints(0, maxLength));
        return normalized;
    }

    private static void requireOrderNo(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{1,64}"))
            throw new IllegalArgumentException("Alipay order number is invalid");
    }

    private static void requireOptionalIdentity(
            AlipaySettings.Configuration config,
            JsonNode response,
            String orderNo,
            long amountFen) {
        requireOptionalIdentity(config, response, orderNo, Long.valueOf(amountFen));
    }

    private static void requireOptionalIdentity(
            AlipaySettings.Configuration config,
            JsonNode response,
            String orderNo,
            Long amountFen) {
        requireMatchIfPresent("app_id", config.appId(), response);
        requireMatchIfPresent("seller_id", config.sellerId(), response);
        requireMatchIfPresent("out_trade_no", orderNo, response);
        responseCurrency(response);
        String totalAmount = AlipayJson.text(response, "total_amount");
        if (amountFen != null
                && totalAmount != null
                && AlipayAmount.toFen(totalAmount) != amountFen)
            throw new IllegalArgumentException("Alipay response amount does not match the order");
    }

    private static void requireMatchIfPresent(String field, String expected, JsonNode response) {
        String value = AlipayJson.text(response, field);
        if (value != null && !expected.equals(value))
            throw new IllegalArgumentException("Alipay response " + field + " does not match");
    }

    private static void requireExactMatch(String field, String expected, JsonNode response) {
        if (!expected.equals(AlipayJson.requiredText(response, field)))
            throw new IllegalArgumentException("Alipay response " + field + " does not match");
    }

    private static String responseCurrency(JsonNode response) {
        String currency = AlipayJson.text(response, "trans_currency");
        String genericCurrency = AlipayJson.text(response, "currency");
        if (currency != null && genericCurrency != null && !currency.equals(genericCurrency))
            throw new IllegalArgumentException("Alipay response currency fields do not match");
        if (currency == null) currency = genericCurrency;
        if (currency == null) return CURRENCY;
        if (!CURRENCY.equals(currency))
            throw new IllegalArgumentException("Alipay response currency is invalid");
        return currency;
    }

    private static void validateCodeUrl(String value) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Alipay QR code URL is invalid");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"qr.alipay.com".equalsIgnoreCase(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getRawPath() == null
                || uri.getRawPath().isBlank()
                || value.length() > 512)
            throw new IllegalArgumentException("Alipay QR code URL is invalid");
    }

    private static Instant parseOptionalTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value, ALIPAY_TIME).atZone(ALIPAY_ZONE).toInstant();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Alipay response time is invalid");
        }
    }
}
