package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class WechatProviderClientTest {
    private static final byte[] SECRET = "synthetic-app-secret".getBytes(StandardCharsets.UTF_8);

    @Test
    void springCanConstructTheProductionProviderClient() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(WechatProviderClient.class)
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertNotNull(context.getBean(WechatProviderClient.class));
                        });
    }

    @Test
    void exchangesTextIdentityAndUsesMatchingOptionalProfile() throws Exception {
        try (Fixture fixture =
                fixture(
                        json("{\"openid\":\"open-1\",\"access_token\":\"token-1\"}"),
                        json("{\"openid\":\"open-1\",\"nickname\":\"Fixture\"}"))) {
            var identity = fixture.client().exchange("app-fixture", SECRET, "code-fixture", 3);

            assertEquals("open-1", identity.openId());
            assertEquals("Fixture", identity.nickname());
        }
    }

    @Test
    void rejectsNonTextualOpenIdAndAccessToken() throws Exception {
        try (Fixture fixture = fixture(json("{\"openid\":12,\"access_token\":\"token\"}"), null)) {
            var error =
                    assertThrows(
                            ResponseStatusException.class,
                            () ->
                                    fixture.client()
                                            .exchange("app-fixture", SECRET, "code-fixture", 2));
            assertEquals("wechatProviderUnavailable", error.getReason());
        }
    }

    @Test
    void requiresProviderErrorCodeToBeAnIntegerZero() throws Exception {
        for (String errcode : List.of("0.5", "\"0\"", "null")) {
            try (Fixture fixture =
                    fixture(
                            json(
                                    "{\"errcode\":"
                                            + errcode
                                            + ",\"openid\":\"open-1\",\"access_token\":\"token-1\"}"),
                            null)) {
                var error =
                        assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        fixture.client()
                                                .exchange(
                                                        "app-fixture", SECRET, "code-fixture", 2));
                assertEquals("wechatProviderUnavailable", error.getReason());
            }
        }
    }

    @Test
    void rejectsOversizedProviderBodyAndCancelsItsSubscription() {
        AtomicBoolean cancelled = new AtomicBoolean();
        var subscriber = new WechatProviderClient.LimitedBodySubscriber(4);
        subscriber.onSubscribe(
                new Flow.Subscription() {
                    @Override
                    public void request(long count) {}

                    @Override
                    public void cancel() {
                        cancelled.set(true);
                    }
                });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[5])));

        assertTrue(cancelled.get());
        assertThrows(CompletionException.class, () -> subscriber.getBody().join());
    }

    @Test
    void rejectsHttpErrorsAndErrorJsonWithoutSurfacingProviderDetails() throws Exception {
        try (Fixture fixture =
                fixture(500, json("{\"errcode\":40013,\"errmsg\":\"secret detail\"}"), null)) {
            var error =
                    assertThrows(
                            ResponseStatusException.class,
                            () ->
                                    fixture.client()
                                            .exchange("app-fixture", SECRET, "code-fixture", 2));
            assertEquals("wechatProviderUnavailable", error.getReason());
            assertFalse(error.getMessage().contains("secret detail"));
        }
        try (Fixture fixture =
                fixture(json("{\"errcode\":40013,\"errmsg\":\"secret detail\"}"), null)) {
            var error =
                    assertThrows(
                            ResponseStatusException.class,
                            () ->
                                    fixture.client()
                                            .exchange("app-fixture", SECRET, "code-fixture", 2));
            assertEquals("wechatProviderUnavailable", error.getReason());
            assertFalse(error.getMessage().contains("secret detail"));
        }
    }

    @Test
    void optionalProfileTimeoutDoesNotDiscardVerifiedIdentityAndUsesOneDeadline() throws Exception {
        try (Fixture fixture = slowFixture()) {
            long started = System.nanoTime();
            var identity = fixture.client().exchange("app-fixture", SECRET, "code-fixture", 1);
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertEquals("open-1", identity.openId());
            assertNull(identity.nickname());
            assertTrue(
                    elapsedMillis < 1_500,
                    "full response body must respect the single operation deadline");
        }
    }

    @Test
    void tokenBodyThatExceedsTheOperationDeadlineCannotAuthenticate() throws Exception {
        try (Fixture fixture = slowTokenFixture()) {
            long started = System.nanoTime();
            var error =
                    assertThrows(
                            ResponseStatusException.class,
                            () ->
                                    fixture.client()
                                            .exchange("app-fixture", SECRET, "code-fixture", 1));
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertEquals("wechatProviderUnavailable", error.getReason());
            assertTrue(
                    elapsedMillis < 1_500, "a slow token body must respect the operation deadline");
        }
    }

    private static Fixture fixture(String tokenBody, String userInfoBody) throws IOException {
        return fixture(200, tokenBody, userInfoBody);
    }

    private static Fixture fixture(int status, String tokenBody, String userInfoBody)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> respond(exchange, status, tokenBody));
        server.createContext("/userinfo", exchange -> respond(exchange, 200, userInfoBody));
        server.start();
        return new Fixture(server);
    }

    private static Fixture slowFixture() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/token",
                exchange -> {
                    byte[] start = "{\"openid\":\"open-1\",".getBytes(StandardCharsets.UTF_8);
                    byte[] end = "\"access_token\":\"token-1\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    try (var out = exchange.getResponseBody()) {
                        out.write(start);
                        out.flush();
                        Thread.sleep(650);
                        out.write(end);
                        out.flush();
                    } catch (IOException | InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
        server.createContext(
                "/userinfo",
                exchange -> {
                    byte[] start =
                            "{\"openid\":\"open-1\",\"nickname\":".getBytes(StandardCharsets.UTF_8);
                    byte[] end = "\"Fixture\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    try (var out = exchange.getResponseBody()) {
                        out.write(start);
                        out.flush();
                        Thread.sleep(1_000);
                        out.write(end);
                        out.flush();
                    } catch (IOException | InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
        server.start();
        return new Fixture(server);
    }

    private static Fixture slowTokenFixture() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/token",
                exchange -> {
                    byte[] body =
                            "{\"openid\":\"open-1\",\"access_token\":\"token-1\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body, 0, 12);
                        out.flush();
                        Thread.sleep(1_200);
                        out.write(body, 12, body.length - 12);
                        out.flush();
                    } catch (IOException | InterruptedException ignored) {
                        if (ignored instanceof InterruptedException)
                            Thread.currentThread().interrupt();
                    }
                });
        server.createContext("/userinfo", exchange -> respond(exchange, 200, null));
        server.start();
        return new Fixture(server);
    }

    private static void respond(HttpExchange exchange, int status, String response)
            throws IOException {
        byte[] body = response == null ? new byte[0] : response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String json(String value) {
        return value;
    }

    private record Fixture(HttpServer server) implements AutoCloseable {
        URI uri(String path) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        }

        WechatProviderClient client() {
            var http =
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(2))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
            return new WechatProviderClient(
                    new ObjectMapper(), http, uri("/token"), uri("/userinfo"));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
