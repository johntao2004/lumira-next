package com.lumira.plugins.verificationdebugger;

import com.lumira.plugin.sdk.PluginResources;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.ObjectMapper;

/** Loopback-only receiver, activated by the host's post-publication resource scheduler. */
final class VerificationDebuggerReceiver implements AutoCloseable {
    static final int MAX_BODY_BYTES = 8 * 1024;

    record HttpResponse(int status, Object body) {}

    @FunctionalInterface
    interface Handler {
        HttpResponse handle(String path, String accessKeyId, String authorization, byte[] body);
    }

    private final VerificationDebuggerGate gate;
    private final Handler handler;
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;
    private String baseEndpoint;
    private boolean closed;

    VerificationDebuggerReceiver(VerificationDebuggerGate gate, Handler handler) {
        this.gate = gate;
        this.handler = handler;
    }

    static VerificationDebuggerReceiver register(
            PluginResources resources, VerificationDebuggerGate gate, Handler handler) {
        return register(resources, gate, handler, () -> {});
    }

    static VerificationDebuggerReceiver register(
            PluginResources resources,
            VerificationDebuggerGate gate,
            Handler handler,
            Runnable maintenance) {
        var receiver = new VerificationDebuggerReceiver(gate, handler);
        resources.own("verification-debugger-receiver", receiver);
        resources.schedule(
                "verification-debugger-activation",
                Duration.ofMillis(100),
                () -> {
                    receiver.activateIfEnabled();
                    maintenance.run();
                });
        return receiver;
    }

    synchronized void activateIfEnabled() {
        if (closed || server != null || !gate.enabled()) return;
        HttpServer candidate = null;
        ExecutorService workers = null;
        try {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            candidate = HttpServer.create(new InetSocketAddress(loopback, 0), 8);
            workers =
                    new ThreadPoolExecutor(
                            2,
                            2,
                            0,
                            TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<>(8),
                            Thread.ofPlatform()
                                    .name("lumira-sms-debug-http-", 0)
                                    .daemon(true)
                                    .factory(),
                            new ThreadPoolExecutor.CallerRunsPolicy());
            candidate.createContext("/", this::handleExchange);
            candidate.setExecutor(workers);
            candidate.start();
            server = candidate;
            executor = workers;
            baseEndpoint = "http://127.0.0.1:" + candidate.getAddress().getPort();
        } catch (IOException | RuntimeException failure) {
            if (candidate != null) candidate.stop(0);
            if (workers != null) workers.shutdownNow();
        }
    }

    synchronized boolean ready() {
        return !closed && server != null;
    }

    synchronized String baseEndpoint() {
        return ready() ? baseEndpoint : null;
    }

    synchronized String endpoint(String path) {
        if (!ready()) return null;
        return switch (path) {
            case "send", "aliyun", "tencent" -> baseEndpoint + "/" + path;
            default -> null;
        };
    }

    private void handleExchange(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        int status = 400;
        Object body = Map.of("error", "invalid_request");
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
            status = 403;
            body = Map.of("error", "loopback_only");
        } else if (exchange.getRequestHeaders().getFirst("Origin") != null) {
            status = 403;
            body = Map.of("error", "origin_forbidden");
        } else if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            status = 405;
            body = Map.of("error", "method_not_allowed");
        } else if (exchange.getRequestURI().getRawQuery() != null) {
            status = 404;
            body = Map.of("error", "not_found");
        } else if (!isSupportedPath(path)) {
            status = 404;
            body = Map.of("error", "not_found");
        } else if (!contentTypeIsJson(exchange)) {
            status = 415;
            body = Map.of("error", "json_required");
        } else if (contentLengthTooLarge(exchange)) {
            status = 413;
            body = Map.of("error", "request_too_large");
        } else {
            byte[] requestBody = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            try {
                if (requestBody.length > MAX_BODY_BYTES) {
                    status = 413;
                    body = Map.of("error", "request_too_large");
                } else {
                    HttpResponse result =
                            handler.handle(
                                    path.substring(1),
                                    exchange.getRequestHeaders()
                                            .getFirst("X-Lumira-Debug-Access-Key"),
                                    exchange.getRequestHeaders()
                                            .getFirst(HttpHeaders.AUTHORIZATION),
                                    requestBody);
                    status = result.status();
                    body = result.body();
                }
            } finally {
                Arrays.fill(requestBody, (byte) 0);
            }
        }
        writeResponse(exchange, status, body);
    }

    private static boolean isSupportedPath(String path) {
        return "/send".equals(path) || "/aliyun".equals(path) || "/tencent".equals(path);
    }

    private static boolean contentTypeIsJson(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        return contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json");
    }

    private static boolean contentLengthTooLarge(HttpExchange exchange) {
        String rawLength = exchange.getRequestHeaders().getFirst(HttpHeaders.CONTENT_LENGTH);
        if (rawLength == null) return false;
        try {
            return Long.parseLong(rawLength) > MAX_BODY_BYTES;
        } catch (NumberFormatException invalidLength) {
            return true;
        }
    }

    private void writeResponse(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (RuntimeException serializationFailure) {
            status = 500;
            bytes = "{\"error\":\"internal_error\"}".getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders()
                .set(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
            exchange.close();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        HttpServer running = server;
        ExecutorService workers = executor;
        server = null;
        executor = null;
        baseEndpoint = null;
        if (running != null) running.stop(1);
        if (workers != null) {
            workers.shutdown();
            try {
                if (!workers.awaitTermination(2, TimeUnit.SECONDS)) workers.shutdownNow();
            } catch (InterruptedException interrupted) {
                workers.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
