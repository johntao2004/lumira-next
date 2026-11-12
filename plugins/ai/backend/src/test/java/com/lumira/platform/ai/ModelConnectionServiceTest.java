package com.lumira.platform.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumira.platform.ai.AiProviderProfile.ProviderKind;
import com.lumira.platform.common.SecretStorePort;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class ModelConnectionServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void parsesActualIdsAndModelMetadataFromCompatibleModelsEndpoint() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        startServer(
                exchange -> {
                    request.set(
                            exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                    respond(
                            exchange,
                            200,
                            """
                {"data":[
                  {"id":"model-a","name":"Model A","input_modalities":["text","image"],"output_modalities":["text"]},
                  {"id":"model-b","owned_by":"local"}
                ]}
                """);
                });

        var result = service(Duration.ofSeconds(2)).probe(profile(baseUrl(), "configured"));

        assertEquals("GET /v1/models", request.get());
        assertEquals(
                List.of("model-a", "model-b"),
                result.models().stream().map(ModelConnectionService.ModelOption::id).toList());
        assertEquals("Model A", result.models().get(0).name());
        assertEquals(List.of("text", "image"), result.models().get(0).inputModalities());
        assertEquals(List.of("text"), result.models().get(0).outputModalities());
        assertEquals("model-b", result.models().get(1).name());
        assertFalse(result.configuredModelAvailable());
    }

    @Test
    void returnsEmptyListForSuccessfulEmptyCatalog() throws Exception {
        startServer(exchange -> respond(exchange, 200, "{\"data\":[]}"));
        var result = service(Duration.ofSeconds(2)).probe(profile(baseUrl(), "model-a"));
        assertTrue(result.models().isEmpty());
        assertFalse(result.configuredModelAvailable());
    }

    @Test
    void mapsAuthenticationEndpointAndRateLimitErrorsWithoutExposingBody() throws Exception {
        assertSafeFailure(
                401, "provider secret must never be reflected", "aiProviderAuthenticationFailed");
        assertSafeFailure(404, "secret response body", "aiModelDiscoveryEndpointUnavailable");
        assertSafeFailure(429, "private vendor message", "aiProviderRateLimited");
    }

    @Test
    void mapsTimeoutToStableReasonWithoutProviderBody() throws Exception {
        startServer(
                exchange -> {
                    try {
                        Thread.sleep(350);
                        respond(exchange, 200, "{\"data\":[]}");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } catch (IOException ignored) {
                        // The client has already timed out and closed the exchange.
                    }
                });

        ResponseStatusException failure =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service(Duration.ofMillis(80)).probe(profile(baseUrl(), "model-a")));

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, failure.getStatusCode());
        assertEquals("aiProviderRequestTimedOut", failure.getReason());
        assertFalse(failure.getMessage().contains("provider"));
    }

    @Test
    void totalDeadlineClosesAResponseBodyThatKeepsDripping() throws Exception {
        AtomicInteger written = new AtomicInteger();
        startServer(
                exchange -> {
                    try {
                        exchange.sendResponseHeaders(200, 10);
                        for (int index = 0; index < 10; index++) {
                            exchange.getResponseBody().write(' ');
                            exchange.getResponseBody().flush();
                            written.incrementAndGet();
                            Thread.sleep(30);
                        }
                    } catch (IOException disconnected) {
                        // The total request deadline actively closes a body that never completes.
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        long started = System.nanoTime();

        ResponseStatusException failure =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service(Duration.ofMillis(100)).probe(profile(baseUrl(), "fixture")));

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, failure.getStatusCode());
        assertEquals("aiProviderRequestTimedOut", failure.getReason());
        assertTrue(elapsedMillis < 1000, "deadline should cover the response body");
        assertTrue(written.get() < 10, "the client should close the incomplete response");
    }

    @Test
    void deepSeekUsesOfficialRootModelsPathAndCapabilitiesStayProviderScoped() {
        var deepSeek =
                new AiProviderProfile(
                        UUID.randomUUID(),
                        "DeepSeek",
                        ProviderKind.DEEPSEEK,
                        "https://api.deepseek.com/",
                        "deepseek-flash",
                        List.of("VISION", "TOOL_CALLING"),
                        true,
                        1);
        assertEquals(
                "https://api.deepseek.com/models",
                ModelConnectionService.apiUrl(deepSeek, "/models"));
        assertEquals(List.of("CHAT"), deepSeek.providerCapabilityCodes());
        assertEquals(List.of("CHAT", "TOOL_CALLING"), deepSeek.capabilityCodes());

        var jev = profile("https://127.0.0.1", "jev-latest", ProviderKind.TYPESAFE_JEV);
        assertEquals(List.of("CHOICE", "SCORE", "NOUL"), jev.providerCapabilityCodes());
        assertEquals(List.of("CHOICE", "SCORE", "NOUL"), jev.capabilityCodes());
        for (ProviderKind kind : ProviderKind.values()) {
            List<String> capabilities = kind.providerCapabilityCodes();
            assertFalse(
                    capabilities.contains("VISION"), kind + " must not infer model vision support");
            assertFalse(
                    capabilities.contains("TOOL_CALLING"),
                    kind + " must not infer model tool support");
            assertEquals(
                    kind == ProviderKind.TYPESAFE_JEV,
                    capabilities.contains("CHAT") == false,
                    kind + " chat capability must follow its adapter contract");
        }
    }

    private void assertSafeFailure(int status, String body, String reason) throws Exception {
        startServer(exchange -> respond(exchange, status, body));
        ResponseStatusException failure =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service(Duration.ofSeconds(2)).probe(profile(baseUrl(), "model-a")));
        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        assertEquals(reason, failure.getReason());
        assertFalse(failure.getMessage().contains(body));
        stopServer();
        server = null;
    }

    private void startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", handler);
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private ModelConnectionService service(Duration timeout) {
        SecretStorePort emptySecrets =
                new SecretStorePort() {
                    @Override
                    public State state(String context) {
                        return new State(0, false);
                    }

                    @Override
                    public Optional<byte[]> reveal(String context) {
                        return Optional.empty();
                    }

                    @Override
                    public State save(String context, long expectedVersion, byte[] plaintext) {
                        throw new UnsupportedOperationException();
                    }
                };
        return new ModelConnectionService(emptySecrets, new ObjectMapper(), timeout);
    }

    private static AiProviderProfile profile(String baseUrl, String model) {
        return profile(baseUrl, model, ProviderKind.LM_STUDIO);
    }

    private static AiProviderProfile profile(String baseUrl, String model, ProviderKind kind) {
        return new AiProviderProfile(
                UUID.randomUUID(),
                "Fixture provider",
                kind,
                baseUrl,
                model,
                List.of("VISION", "TOOL_CALLING", "EMBEDDING"),
                true,
                1);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
