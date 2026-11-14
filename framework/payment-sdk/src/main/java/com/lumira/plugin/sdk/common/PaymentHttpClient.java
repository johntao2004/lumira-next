package com.lumira.plugin.sdk.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 供可信支付适配器使用的受限 HTTPS 传输。 Bounded HTTPS transport for trusted payment provider adapters. */
public final class PaymentHttpClient implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private final URI origin;
    private final HttpClient client;
    private final Duration requestTimeout;
    private final int maxResponseBytes;

    public PaymentHttpClient(
            URI origin, Duration connectTimeout, Duration requestTimeout, int maxResponseBytes) {
        this.origin = validateOrigin(origin);
        this.requestTimeout = requireDuration(requestTimeout, "requestTimeout");
        if (maxResponseBytes < 1 || maxResponseBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid response size limit");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(requireDuration(connectTimeout, "connectTimeout"))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    /**
     * 仅请求同源绝对路径；返回重定向但不跟随。 Sends only to a same-origin absolute path. Redirects are returned and
     * never followed.
     */
    public Response send(
            String method, String pathAndQuery, Map<String, String> headers, byte[] requestBody) {
        String normalizedMethod = validateMethod(method);
        URI target = resolve(pathAndQuery);
        byte[] body = requestBody == null ? new byte[0] : requestBody;
        if (body.length > MAX_REQUEST_BYTES)
            throw new IllegalArgumentException("Payment request is too large");
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(requestTimeout);
        Map<String, String> safeHeaders = headers == null ? Map.of() : headers;
        for (Map.Entry<String, String> entry : safeHeaders.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (!isHeaderName(name) || value == null || containsControl(value)) {
                throw new IllegalArgumentException("Invalid payment HTTP header");
            }
            builder.header(name, value);
        }
        builder.header("Accept", "application/json");
        HttpRequest.BodyPublisher publisher =
                body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body);
        builder.method(normalizedMethod, publisher);
        CompletableFuture<HttpResponse<byte[]>> pending =
                client.sendAsync(
                        builder.build(), info -> new LimitedBodySubscriber(maxResponseBytes));
        try {
            HttpResponse<byte[]> response =
                    pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            String encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
            if (!encoding.equalsIgnoreCase("identity")) {
                throw new IllegalStateException("Encoded payment responses are not accepted");
            }
            Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
            response.headers()
                    .map()
                    .forEach(
                            (name, values) ->
                                    responseHeaders.put(
                                            name.toLowerCase(Locale.ROOT), List.copyOf(values)));
            return new Response(response.statusCode(), responseHeaders, response.body());
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new IllegalStateException("Payment HTTP request timed out", exception);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Payment HTTP request interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw new IllegalStateException(
                        "Payment HTTP response exceeded its limit", ioException);
            }
            throw new IllegalStateException("Payment HTTP request failed", cause);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    /**
     * 使用 UTF-8 编码表单并保留传入 Map 的遍历顺序。 Encodes a form using UTF-8 and preserves the iteration order of
     * the supplied map.
     */
    public static byte[] encodeForm(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        List<String> pairs = new ArrayList<>(values.size());
        values.forEach(
                (key, value) -> {
                    if (key == null || value == null)
                        throw new IllegalArgumentException("Null form value");
                    pairs.add(
                            URLEncoder.encode(key, StandardCharsets.UTF_8)
                                    + "="
                                    + URLEncoder.encode(value, StandardCharsets.UTF_8));
                });
        byte[] body = String.join("&", pairs).getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_REQUEST_BYTES)
            throw new IllegalArgumentException("Payment form is too large");
        return body;
    }

    public static String formContentType() {
        return "application/x-www-form-urlencoded; charset=UTF-8";
    }

    private URI resolve(String pathAndQuery) {
        if (pathAndQuery == null
                || pathAndQuery.isBlank()
                || pathAndQuery.charAt(0) != '/'
                || pathAndQuery.startsWith("//")
                || pathAndQuery.indexOf('\\') >= 0
                || containsControl(pathAndQuery)) {
            throw new IllegalArgumentException(
                    "Payment request path must be absolute and same-origin");
        }
        URI relative;
        try {
            relative = URI.create(pathAndQuery);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid payment request path", exception);
        }
        if (relative.isAbsolute()
                || relative.getRawAuthority() != null
                || relative.getFragment() != null) {
            throw new IllegalArgumentException("Payment request path must be same-origin");
        }
        URI target = origin.resolve(relative);
        if (!sameOrigin(origin, target))
            throw new IllegalArgumentException("Payment request origin changed");
        return target;
    }

    private static URI validateOrigin(URI origin) {
        Objects.requireNonNull(origin, "origin");
        if (!"https".equalsIgnoreCase(origin.getScheme())
                || origin.getHost() == null
                || origin.getUserInfo() != null
                || origin.getQuery() != null
                || origin.getFragment() != null
                || (origin.getPath() != null
                        && !origin.getPath().isEmpty()
                        && !origin.getPath().equals("/"))) {
            throw new IllegalArgumentException("Payment origin must be a plain HTTPS origin");
        }
        return URI.create(
                "https://"
                        + origin.getHost().toLowerCase(Locale.ROOT)
                        + (origin.getPort() == -1 || origin.getPort() == 443
                                ? ""
                                : ":" + origin.getPort()));
    }

    private static boolean sameOrigin(URI left, URI right) {
        return "https".equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right)
                && right.getUserInfo() == null;
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }

    private static String validateMethod(String method) {
        if (method == null) throw new IllegalArgumentException("Invalid payment HTTP method");
        String normalized = method.toUpperCase(Locale.ROOT);
        if (!normalized.equals("GET") && !normalized.equals("POST") && !normalized.equals("PUT")) {
            throw new IllegalArgumentException("Unsupported payment HTTP method");
        }
        return normalized;
    }

    private static Duration requireDuration(Duration duration, String name) {
        if (duration == null
                || duration.isNegative()
                || duration.isZero()
                || duration.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return duration;
    }

    private static boolean isHeaderName(String value) {
        return value != null
                && !value.isBlank()
                && value.chars()
                        .allMatch(
                                character ->
                                        character > 32
                                                && character < 127
                                                && "()<>@,;:\\\"/[]?={} \t".indexOf(character) < 0);
    }

    private static boolean containsControl(String value) {
        return value != null
                && value.chars().anyMatch(character -> character < 0x20 || character == 0x7f);
    }

    public record Response(int statusCode, Map<String, List<String>> headers, byte[] body) {
        public Response {
            Map<String, List<String>> copied = new LinkedHashMap<>();
            if (headers != null)
                headers.forEach(
                        (name, values) ->
                                copied.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
            headers = Map.copyOf(copied);
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        /**
         * 返回单一响应头值；重复安全响应头会被拒绝。 Returns a single header value; duplicate security headers are
         * rejected.
         */
        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            if (values == null || values.isEmpty()) return null;
            if (values.size() != 1)
                throw new IllegalStateException("Duplicate payment response header");
            return values.getFirst();
        }
    }

    private static final class LimitedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int received;

        private LimitedBodySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            long incoming = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (incoming > maxBytes - received) {
                subscription.cancel();
                body.completeExceptionally(
                        new IOException("Payment response exceeded the size limit"));
                return;
            }
            for (ByteBuffer buffer : buffers) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                output.writeBytes(chunk);
                received += chunk.length;
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }
    }
}
