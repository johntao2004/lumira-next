package com.lumira.platform.identity;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fixed-host WeChat OAuth client with one deadline and a bounded response subscriber. */
@Component
public class WechatProviderClient {
    private static final URI TOKEN_ENDPOINT =
            URI.create("https://api.weixin.qq.com/sns/oauth2/access_token");
    private static final URI USER_INFO_ENDPOINT =
            URI.create("https://api.weixin.qq.com/sns/userinfo");
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;
    private final HttpClient http;
    private final ObjectMapper json;
    private final URI tokenEndpoint;
    private final URI userInfoEndpoint;

    @Autowired
    public WechatProviderClient(ObjectMapper json) {
        this(
                json,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                TOKEN_ENDPOINT,
                USER_INFO_ENDPOINT);
    }

    WechatProviderClient(
            ObjectMapper json, HttpClient http, URI tokenEndpoint, URI userInfoEndpoint) {
        this.json = json;
        this.http = http;
        this.tokenEndpoint = tokenEndpoint;
        this.userInfoEndpoint = userInfoEndpoint;
    }

    public Identity exchange(String appId, byte[] secret, String code, int timeoutSeconds) {
        if (appId == null
                || !appId.matches("[A-Za-z0-9_-]{1,64}")
                || secret == null
                || secret.length == 0
                || code == null
                || !code.matches("[\\x21-\\x7e]{1,512}")
                || timeoutSeconds < 1
                || timeoutSeconds > 30) throw unavailable();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        String appSecret = new String(secret, StandardCharsets.UTF_8);
        JsonNode token =
                parseAndClear(
                        send(
                                tokenEndpoint,
                                Map.of(
                                        "appid", appId,
                                        "secret", appSecret,
                                        "code", code,
                                        "grant_type", "authorization_code"),
                                deadline));
        if (hasProviderError(token)) throw unavailable();
        String openId = textualValue(token.get("openid"), 256);
        String accessToken = textualValue(token.get("access_token"), 4096);
        if (openId == null || accessToken == null) throw unavailable();

        String nickname = null;
        if (remainingNanos(deadline) > 0) {
            try {
                JsonNode profile =
                        parseAndClear(
                                send(
                                        userInfoEndpoint,
                                        Map.of(
                                                "access_token",
                                                accessToken,
                                                "openid",
                                                openId,
                                                "lang",
                                                "zh_CN"),
                                        deadline));
                if (!hasProviderError(profile)
                        && openId.equals(textualValue(profile.get("openid"), 256)))
                    nickname = safeNickname(textualValue(profile.get("nickname"), 256));
            } catch (ResponseStatusException ignored) {
                // Nickname is an optional display hint. The validated openid came from the code
                // exchange.
            }
        }
        return new Identity(openId, nickname);
    }

    private byte[] send(URI endpoint, Map<String, String> parameters, long deadline) {
        long remaining = remainingNanos(deadline);
        if (remaining <= 0) throw unavailable();
        URI uri;
        try {
            StringBuilder query = new StringBuilder(endpoint.toString()).append('?');
            for (var entry : parameters.entrySet()) {
                if (query.charAt(query.length() - 1) != '?') query.append('&');
                query.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            }
            uri = URI.create(query.toString());
        } catch (RuntimeException invalid) {
            throw unavailable();
        }
        Duration timeout = Duration.ofNanos(remaining);
        var currentSubscriber = new AtomicReference<LimitedBodySubscriber>();
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(uri)
                            .timeout(timeout)
                            .header("Accept", "application/json")
                            .header("User-Agent", "Lumira-Next-Identity")
                            .GET()
                            .build();
            future =
                    http.sendAsync(
                            request,
                            info -> {
                                Long length =
                                        info.headers()
                                                        .firstValueAsLong("Content-Length")
                                                        .isPresent()
                                                ? info.headers()
                                                        .firstValueAsLong("Content-Length")
                                                        .getAsLong()
                                                : null;
                                LimitedBodySubscriber subscriber =
                                        new LimitedBodySubscriber(MAX_RESPONSE_BYTES);
                                currentSubscriber.set(subscriber);
                                if (length != null && length > MAX_RESPONSE_BYTES)
                                    subscriber.rejectOversized();
                                return subscriber;
                            });
            long waitNanos = remainingNanos(deadline);
            if (waitNanos <= 0) throw new TimeoutException();
            HttpResponse<byte[]> response = future.get(waitNanos, TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200 || response.body().length > MAX_RESPONSE_BYTES)
                throw unavailable();
            return response.body();
        } catch (TimeoutException timeoutError) {
            if (future != null) future.cancel(true);
            LimitedBodySubscriber subscriber = currentSubscriber.get();
            if (subscriber != null) subscriber.cancel();
            throw unavailable();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (future != null) future.cancel(true);
            LimitedBodySubscriber subscriber = currentSubscriber.get();
            if (subscriber != null) subscriber.cancel();
            throw unavailable();
        } catch (ExecutionException | RuntimeException providerError) {
            if (future != null) future.cancel(true);
            LimitedBodySubscriber subscriber = currentSubscriber.get();
            if (subscriber != null) subscriber.cancel();
            if (providerError instanceof ResponseStatusException safe) throw safe;
            throw unavailable();
        }
    }

    private JsonNode parse(byte[] body) {
        try {
            var decoder =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return json.readTree(decoder.decode(ByteBuffer.wrap(body)).toString());
        } catch (CharacterCodingException | RuntimeException malformed) {
            throw unavailable();
        }
    }

    private JsonNode parseAndClear(byte[] body) {
        try {
            return parse(body);
        } finally {
            java.util.Arrays.fill(body, (byte) 0);
        }
    }

    private static boolean hasProviderError(JsonNode response) {
        if (response == null || !response.isObject()) return true;
        JsonNode error = response.get("errcode");
        return error != null && (!error.isIntegralNumber() || error.intValue() != 0);
    }

    private static String textualValue(JsonNode value, int maximum) {
        if (value == null || !value.isTextual()) return null;
        String text = value.textValue();
        return text == null
                        || text.isBlank()
                        || text.length() > maximum
                        || text.codePoints().anyMatch(Character::isISOControl)
                ? null
                : text;
    }

    private static String safeNickname(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 256
                || value.codePoints().anyMatch(Character::isISOControl)) return null;
        return value.strip();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static long remainingNanos(long deadline) {
        return deadline - System.nanoTime();
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "wechatProviderUnavailable");
    }

    public record Identity(String openId, String nickname) {}

    /** Backpressure-aware subscriber that cancels once the full streamed body crosses the limit. */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final WipingBodyBuffer bytes = new WipingBodyBuffer();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedBodySubscriber(int maximum) {
            this.maximum = maximum;
        }

        @Override
        public CompletableFuture<byte[]> getBody() {
            return body;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            if (body.isDone()) subscription.cancel();
            else subscription.request(1);
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                int length = buffer.remaining();
                if (length > maximum - bytes.size()) {
                    rejectOversized();
                    return;
                }
                byte[] chunk = new byte[length];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
                java.util.Arrays.fill(chunk, (byte) 0);
            }
            if (subscription != null) subscription.request(1);
        }

        @Override
        public synchronized void onError(Throwable error) {
            bytes.clear();
            body.completeExceptionally(error);
        }

        @Override
        public synchronized void onComplete() {
            byte[] response = bytes.takeAndClear();
            if (!body.complete(response)) java.util.Arrays.fill(response, (byte) 0);
        }

        synchronized void rejectOversized() {
            if (subscription != null) subscription.cancel();
            bytes.clear();
            body.completeExceptionally(new IllegalStateException("providerResponseTooLarge"));
        }

        synchronized void cancel() {
            if (subscription != null) subscription.cancel();
            bytes.clear();
            body.cancel(true);
        }
    }

    private static final class WipingBodyBuffer extends ByteArrayOutputStream {
        synchronized byte[] takeAndClear() {
            byte[] result = toByteArray();
            clear();
            return result;
        }

        synchronized void clear() {
            java.util.Arrays.fill(buf, (byte) 0);
            reset();
        }
    }
}
