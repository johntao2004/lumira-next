package com.lumira.platform.sms;

import com.lumira.platform.common.GuardedSockets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Cloud provider requests use pinned public HTTPS; responses and credentials never enter logs. */
@Component
public class SmsGateway {
    public enum Result {
        ACCEPTED,
        AUTHENTICATION_FAILED,
        PROVIDER_REJECTED,
        CONNECTION_FAILED,
        DELIVERY_UNCERTAIN
    }

    @FunctionalInterface
    interface Exchange {
        GuardedSockets.Response send(SmsProviderProtocol.Request request, Duration timeout)
                throws java.io.IOException, InterruptedException;
    }

    private final Exchange exchange;
    private final ObjectMapper mapper;

    @Autowired
    public SmsGateway(ObjectMapper mapper) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                mapper);
    }

    SmsGateway(HttpClient client, ObjectMapper mapper) {
        this(
                mapper,
                (request, timeout) -> {
                    URI target = request.uri();
                    boolean loopback =
                            java.util.Set.of("localhost", "127.0.0.1", "::1", "[::1]")
                                    .contains(target.getHost());
                    if (!loopback)
                        return GuardedSockets.exchange(
                                target,
                                request.method(),
                                request.headers(),
                                request.body(),
                                timeout,
                                32768);
                    var builder = HttpRequest.newBuilder(target).timeout(timeout);
                    request.headers().forEach(builder::header);
                    var publisher =
                            request.body() == null
                                    ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofByteArray(request.body());
                    var response =
                            client.send(
                                    builder.method(request.method(), publisher).build(),
                                    HttpResponse.BodyHandlers.ofInputStream());
                    try (var stream = response.body()) {
                        return new GuardedSockets.Response(
                                response.statusCode(), stream.readNBytes(32768));
                    }
                });
    }

    SmsGateway(ObjectMapper mapper, Exchange exchange) {
        this.mapper = mapper;
        this.exchange = exchange;
    }

    public Result send(SmsConnection connection, OutgoingSms sms) {
        byte[] secret = connection.secret();
        try (connection) {
            var data = connection.settings();
            if (!data.enabled() || sms == null || secret == null || secret.length == 0)
                return Result.CONNECTION_FAILED;
            String credential = new String(secret, StandardCharsets.UTF_8);
            if (credential.isBlank() || credential.codePoints().anyMatch(Character::isISOControl))
                return Result.AUTHENTICATION_FAILED;
            SmsProviderProtocol.Request request;
            try {
                request =
                        SmsProviderProtocol.request(
                                data,
                                sms,
                                credential,
                                Instant.now(),
                                UUID.randomUUID().toString().replace("-", ""));
            } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
                return Result.PROVIDER_REJECTED;
            }
            try {
                var response = exchange.send(request, Duration.ofSeconds(data.timeoutSeconds()));
                return classify(data.provider(), response.status(), response.body());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Result.DELIVERY_UNCERTAIN;
            } catch (java.io.IOException failed) {
                // A timeout/read failure after writing may already have sent the SMS. Never resend
                // it automatically.
                if (failed instanceof java.net.ConnectException
                        || failed instanceof java.net.UnknownHostException
                        || failed instanceof java.net.http.HttpConnectTimeoutException
                        || failed instanceof javax.net.ssl.SSLHandshakeException)
                    return Result.CONNECTION_FAILED;
                return Result.DELIVERY_UNCERTAIN;
            } finally {
                if (request.body() != null) Arrays.fill(request.body(), (byte) 0);
            }
        } finally {
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
    }

    Result classify(String provider, int status, byte[] body) {
        if (status == 401 || status == 403) return Result.AUTHENTICATION_FAILED;
        if (provider.equals("webhook")) {
            if (status >= 200 && status < 300) return Result.ACCEPTED;
            if (status >= 400 && status < 500 && status != 408 && status != 429)
                return Result.DELIVERY_UNCERTAIN;
            return Result.CONNECTION_FAILED;
        }
        if (status == 429) return Result.CONNECTION_FAILED;
        if (status >= 500 || status == 408 || status >= 300 && status < 400)
            return Result.DELIVERY_UNCERTAIN;
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null) return Result.DELIVERY_UNCERTAIN;
            String code;
            switch (provider) {
                case "tencent" -> {
                    var response = root.path("Response");
                    if (!response.path("Error").isMissingNode())
                        code = response.path("Error").path("Code").asString();
                    else {
                        var items = response.path("SendStatusSet");
                        if (!items.isArray() || items.size() != 1) return Result.DELIVERY_UNCERTAIN;
                        code = items.get(0).path("Code").asString();
                        if (status == 200 && code.equals("Ok")) return Result.ACCEPTED;
                    }
                }
                case "aliyun" -> {
                    code = root.path("Code").asString();
                    if (status == 200 && code.equals("OK")) return Result.ACCEPTED;
                }
                case "alibaba_intl" -> {
                    code = root.path("ResponseCode").asString();
                    if (code.isEmpty()) code = root.path("Code").asString();
                    if (status == 200 && code.equals("OK")) return Result.ACCEPTED;
                }
                case "huawei" -> {
                    code = root.path("code").asString();
                    if (status == 200 && code.equals("000000")) {
                        var items = root.path("result");
                        if (!items.isArray() || items.size() != 1) return Result.DELIVERY_UNCERTAIN;
                        code = items.get(0).path("status").asString();
                        if (code.equals("000000")) return Result.ACCEPTED;
                    }
                }
                case "twilio" -> {
                    code = root.path("code").asString();
                    if (status == 201
                            && root.path("sid").asString().matches("SM[0-9a-fA-F]{32}")
                            && java.util.Set.of(
                                            "queued", "accepted", "sending", "sent", "delivered")
                                    .contains(root.path("status").asString()))
                        return Result.ACCEPTED;
                }
                default -> {
                    return Result.PROVIDER_REJECTED;
                }
            }
            String lower = code.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("authfailure")
                    || lower.contains("invalidaccesskey")
                    || lower.contains("signaturedoesnotmatch")
                    || lower.contains("invalidsecurity")
                    || lower.startsWith("unauthorized")
                    || lower.startsWith("isv.permission")
                    || code.matches("E00010[1-6]")
                    || code.equals("20003")) return Result.AUTHENTICATION_FAILED;
            if (lower.contains("limitexceeded")
                    || lower.contains("throttl")
                    || lower.contains("frequency")
                    || code.equals("E200015")) return Result.CONNECTION_FAILED;
            if (code.isEmpty() || status >= 200 && status < 300 && provider.equals("twilio"))
                return Result.DELIVERY_UNCERTAIN;
            return Result.PROVIDER_REJECTED;
        } catch (RuntimeException invalidResponse) {
            return Result.DELIVERY_UNCERTAIN;
        }
    }

    static boolean isLoopbackTestEndpoint(String endpoint) {
        if (endpoint == null || endpoint.length() > 2000) return false;
        try {
            URI uri = URI.create(endpoint);
            return "http".equalsIgnoreCase(uri.getScheme())
                    && "127.0.0.1".equals(uri.getHost())
                    && uri.getPort() > 0
                    && uri.getPort() <= 65535
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && "/send".equals(uri.getRawPath());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
