package com.lumira.plugins.alipaypay;

import com.lumira.plugin.sdk.common.PaymentCrypto;
import com.lumira.plugin.sdk.common.PaymentHttpClient;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed-origin Alipay OpenAPI transport and parameter signing. */
final class AlipayGatewayClient implements AlipayGatewayInvoker {
    static final String GATEWAY_PATH = "/gateway.do";
    private static final String CALLBACK_PATH = "/api/plugins/payment-core/callbacks/alipay-pay";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final ZoneId ALIPAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Clock clock;
    private final PaymentHttpClient productionHttp;
    private final PaymentHttpClient sandboxHttp;

    AlipayGatewayClient() {
        this(Clock.systemUTC());
    }

    AlipayGatewayClient(Clock clock) {
        this.clock = clock;
        this.productionHttp = newHttpClient(AlipaySettings.Environment.PRODUCTION);
        this.sandboxHttp = newHttpClient(AlipaySettings.Environment.SANDBOX);
    }

    @Override
    public void close() {
        productionHttp.close();
        sandboxHttp.close();
    }

    @Override
    public JsonNode invoke(
            AlipaySettings.Configuration config,
            String method,
            Map<String, ?> bizContent,
            String notifyUrl) {
        String bizJson;
        try {
            bizJson = JSON.writeValueAsString(bizContent);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Alipay request could not be encoded");
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("app_id", config.appId());
        parameters.put("method", method);
        parameters.put("format", "JSON");
        parameters.put("charset", "utf-8");
        parameters.put("sign_type", "RSA2");
        parameters.put(
                "timestamp", LocalDateTime.now(clock.withZone(ALIPAY_ZONE)).format(TIMESTAMP));
        parameters.put("version", "1.0");
        if (notifyUrl != null) parameters.put("notify_url", validateNotifyUrl(notifyUrl));
        parameters.put("biz_content", bizJson);
        parameters.put(
                "sign",
                PaymentCrypto.signSha256Rsa(config.privateKey(), canonicalRequest(parameters)));

        PaymentHttpClient http =
                config.environment() == AlipaySettings.Environment.SANDBOX
                        ? sandboxHttp
                        : productionHttp;
        PaymentHttpClient.Response response =
                http.send(
                        "POST",
                        GATEWAY_PATH,
                        Map.of("Content-Type", PaymentHttpClient.formContentType()),
                        PaymentHttpClient.encodeForm(parameters));
        if (response.statusCode() != 200)
            throw new IllegalStateException("Alipay gateway returned an unexpected HTTP status");
        return AlipayJson.verifiedResponse(response.body(), responseName(method), config);
    }

    private static PaymentHttpClient newHttpClient(AlipaySettings.Environment environment) {
        return new PaymentHttpClient(
                URI.create(environment.origin() + "/"),
                CONNECT_TIMEOUT,
                REQUEST_TIMEOUT,
                MAX_RESPONSE_BYTES);
    }

    static String responseName(String method) {
        return method.replace('.', '_') + "_response";
    }

    static String validateNotifyUrl(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Alipay callback URL is required");
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Alipay callback URL is invalid");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getQuery() != null
                || !CALLBACK_PATH.equals(uri.getRawPath()))
            throw new IllegalArgumentException(
                    "Alipay callback URL must be the HTTPS payment-core callback");
        return uri.toASCIIString();
    }

    static String canonicalRequest(Map<String, String> parameters) {
        Map<String, String> sorted = new TreeMap<>(Comparator.naturalOrder());
        parameters.forEach(
                (key, value) -> {
                    if (!"sign".equals(key) && value != null && !value.isEmpty())
                        sorted.put(key, value);
                });
        List<String> fields = new ArrayList<>(sorted.size());
        sorted.forEach((key, value) -> fields.add(key + "=" + value));
        return String.join("&", fields);
    }

    static Map<String, Object> content(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0)
            throw new IllegalArgumentException("Alipay request data is invalid");
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValuePairs.length; index += 2)
            values.put((String) keyValuePairs[index], keyValuePairs[index + 1]);
        return values;
    }
}
