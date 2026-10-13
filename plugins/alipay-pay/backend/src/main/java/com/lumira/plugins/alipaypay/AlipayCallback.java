package com.lumira.plugins.alipaypay;

import com.lumira.plugin.sdk.common.PaymentCrypto;
import com.lumira.plugin.sdk.common.PaymentGatewayPort;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Strict Alipay x-www-form-urlencoded callback parser and RSA2 verifier. */
final class AlipayCallback {
    private static final int MAX_BODY_CHARS = 64 * 1024;
    private static final int MAX_FIELDS = 100;
    private static final ZoneId ALIPAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ALIPAY_TIME =
            new DateTimeFormatterBuilder()
                    .appendPattern("uuuu-MM-dd HH:mm:ss")
                    .toFormatter(Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    private AlipayCallback() {}

    static PaymentGatewayPort.Notification verify(
            PaymentGatewayPort.Settings settings, Map<String, String> headers, String rawBody) {
        AlipaySettings.Configuration config = AlipaySettings.require(settings);
        validateHeaders(headers);
        if (rawBody == null || rawBody.isEmpty() || rawBody.length() > MAX_BODY_CHARS)
            throw new IllegalArgumentException("Alipay callback body size is invalid");
        Map<String, String> params = parseForm(rawBody);
        if (!"RSA2".equals(params.get("sign_type")))
            throw new IllegalArgumentException("Alipay callback must use RSA2");
        String signature = required(params, "sign");
        String signed = canonical(params, true);
        if (!PaymentCrypto.verifySha256Rsa(config.publicKey(), signed, signature))
            throw new IllegalArgumentException("Alipay callback signature is invalid");

        requireEqual(config.appId(), required(params, "app_id"), "app_id");
        requireEqual(config.sellerId(), required(params, "seller_id"), "seller_id");
        String merchantOrderNo = required(params, "out_trade_no");
        if (!merchantOrderNo.matches("[A-Za-z0-9_]{1,64}"))
            throw new IllegalArgumentException("Alipay callback order number is invalid");
        String providerTradeNo = required(params, "trade_no");
        if (!providerTradeNo.matches("[A-Za-z0-9]{8,64}"))
            throw new IllegalArgumentException("Alipay callback trade number is invalid");
        long amountFen = AlipayAmount.toFen(required(params, "total_amount"));
        if (amountFen <= 0) throw new IllegalArgumentException("Alipay callback amount is invalid");
        String currency = params.get("trans_currency");
        String genericCurrency = params.get("currency");
        if (currency != null && genericCurrency != null && !currency.equals(genericCurrency))
            throw new IllegalArgumentException("Alipay callback currency fields do not match");
        if (currency == null) currency = genericCurrency;
        if (currency != null && !"CNY".equals(currency))
            throw new IllegalArgumentException("Alipay callback currency is invalid");
        String status = required(params, "trade_status");
        boolean success = "TRADE_SUCCESS".equals(status) || "TRADE_FINISHED".equals(status);
        if (!success && !"WAIT_BUYER_PAY".equals(status) && !"TRADE_CLOSED".equals(status))
            throw new IllegalArgumentException("Alipay callback trade status is invalid");
        String eventId = required(params, "notify_id");
        String eventTime =
                firstNonblank(
                        params.get("gmt_payment"),
                        params.get("gmt_close"),
                        params.get("notify_time"));
        return new PaymentGatewayPort.Notification(
                merchantOrderNo,
                providerTradeNo,
                amountFen,
                "CNY",
                success,
                eventId,
                parseTime(eventTime));
    }

    private static Map<String, String> parseForm(String rawBody) {
        Map<String, String> result = new LinkedHashMap<>();
        String[] pairs = rawBody.split("&", -1);
        if (pairs.length > MAX_FIELDS)
            throw new IllegalArgumentException("Alipay callback has too many fields");
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            if (equals <= 0)
                throw new IllegalArgumentException("Alipay callback form is malformed");
            String key = decode(pair.substring(0, equals));
            String value = decode(pair.substring(equals + 1));
            if (!key.matches("[A-Za-z0-9_]{1,128}")
                    || value.length() > 8192
                    || value.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Alipay callback field is invalid");
            if (result.putIfAbsent(key, value) != null)
                throw new IllegalArgumentException("Alipay callback contains duplicate fields");
        }
        return Map.copyOf(result);
    }

    private static String decode(String component) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(component.length());
        for (int index = 0; index < component.length(); index++) {
            char value = component.charAt(index);
            if (value == '+') bytes.write(' ');
            else if (value == '%') {
                if (index + 2 >= component.length())
                    throw new IllegalArgumentException("Alipay callback encoding is invalid");
                int high = Character.digit(component.charAt(index + 1), 16);
                int low = Character.digit(component.charAt(index + 2), 16);
                if (high < 0 || low < 0)
                    throw new IllegalArgumentException("Alipay callback encoding is invalid");
                bytes.write((high << 4) | low);
                index += 2;
            } else {
                if (value > 0x7f)
                    throw new IllegalArgumentException(
                            "Alipay callback form must percent-encode UTF-8");
                bytes.write(value);
            }
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Alipay callback encoding is invalid");
        }
    }

    private static String canonical(Map<String, String> params, boolean callback) {
        Map<String, String> sorted = new TreeMap<>(Comparator.naturalOrder());
        params.forEach(
                (key, value) -> {
                    if (!"sign".equals(key)
                            && !(callback && "sign_type".equals(key))
                            && value != null
                            && !value.isEmpty()) sorted.put(key, value);
                });
        List<String> fields = new ArrayList<>(sorted.size());
        sorted.forEach((key, value) -> fields.add(key + "=" + value));
        return String.join("&", fields);
    }

    private static String required(Map<String, String> params, String key) {
        String value = params.get(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Alipay callback field is missing: " + key);
        return value;
    }

    private static String firstNonblank(String first, String second, String third) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        if (third != null && !third.isBlank()) return third;
        throw new IllegalArgumentException("Alipay callback event time is missing");
    }

    private static Instant parseTime(String value) {
        try {
            return LocalDateTime.parse(value, ALIPAY_TIME).atZone(ALIPAY_ZONE).toInstant();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Alipay callback event time is invalid");
        }
    }

    private static void requireEqual(String expected, String actual, String field) {
        if (!expected.equals(actual))
            throw new IllegalArgumentException("Alipay callback " + field + " does not match");
    }

    private static void validateHeaders(Map<String, String> headers) {
        if (headers == null)
            throw new IllegalArgumentException("Alipay callback headers are required");
        rejectDuplicateHeaderNames(headers);
        String contentType = header(headers, "content-type");
        if (contentType == null)
            throw new IllegalArgumentException("Alipay callback content type is required");
        validateContentType(contentType);
        String encoding = header(headers, "content-encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding))
            throw new IllegalArgumentException("Alipay callback content encoding is invalid");
        String length = header(headers, "content-length");
        if (length != null) {
            try {
                long parsedLength = Long.parseLong(length);
                if (parsedLength < 0 || parsedLength > MAX_BODY_CHARS)
                    throw new IllegalArgumentException("Alipay callback body size is invalid");
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Alipay callback content length is invalid");
            }
        }
    }

    private static void rejectDuplicateHeaderNames(Map<String, String> headers) {
        Map<String, String> valuesByName = new LinkedHashMap<>();
        headers.keySet()
                .forEach(
                        name -> {
                            if (name == null || name.isBlank())
                                throw new IllegalArgumentException(
                                        "Alipay callback header is invalid");
                            String normalized = name.toLowerCase(Locale.ROOT);
                            String value = headers.get(name);
                            if (value == null)
                                throw new IllegalArgumentException(
                                        "Alipay callback header is invalid");
                            String previous = valuesByName.putIfAbsent(normalized, value);
                            // Be defensive if a caller supplies both a servlet spelling and a
                            // lowercase alias.
                            // Accept that duplicate representation only when both values are
                            // byte-for-byte equal.
                            if (previous != null && !previous.equals(value))
                                throw new IllegalArgumentException(
                                        "Alipay callback contains conflicting headers");
                        });
    }

    private static void validateContentType(String contentType) {
        String[] parts = contentType.split(";", -1);
        if (!"application/x-www-form-urlencoded".equalsIgnoreCase(parts[0].trim()))
            throw new IllegalArgumentException("Alipay callback content type is invalid");
        boolean charsetSeen = false;
        for (int index = 1; index < parts.length; index++) {
            String parameter = parts[index].trim();
            int equals = parameter.indexOf('=');
            if (equals <= 0)
                throw new IllegalArgumentException("Alipay callback content type is invalid");
            String name = parameter.substring(0, equals).trim();
            String value = parameter.substring(equals + 1).trim();
            if (!"charset".equalsIgnoreCase(name)
                    || charsetSeen
                    || !("utf-8".equalsIgnoreCase(unquote(value))))
                throw new IllegalArgumentException("Alipay callback content type is invalid");
            charsetSeen = true;
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
            return value.substring(1, value.length() - 1);
        return value;
    }

    private static String header(Map<String, String> headers, String expectedName) {
        return headers.entrySet().stream()
                .filter(entry -> expectedName.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
