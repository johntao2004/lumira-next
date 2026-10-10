package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Protocol/client tests use a stub server and do not prove worker process or cgroup isolation. */
class OfficeWorkerClientTest {
    private static final String TOKEN = "a".repeat(96);
    private static final byte[] FILE = {1, 2, 3};
    private static final String PREVIEW_RESPONSE =
            "{\"kind\":\"preview\",\"preview\":{\"text\":\"合约预览\","
                    + "\"truncated\":false},\"dictionary\":null,\"status\":0,"
                    + "\"reason\":null,\"issues\":[]}";

    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private Handler handler;
    private String oldUrl;
    private String oldToken;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> filenameHeader = new AtomicReference<>();

    @BeforeEach
    void startStubServer() throws Exception {
        oldUrl = System.getProperty("lumira.office.worker.url");
        oldToken = System.getProperty("lumira.office.worker.token");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        handler = this::previewResponse;
        server.createContext(
                "/",
                exchange -> {
                    requests.incrementAndGet();
                    filenameHeader.set(
                            exchange.getRequestHeaders()
                                    .getFirst(OfficeWorkerProtocol.FILENAME_HEADER));
                    exchange.getRequestBody().readAllBytes();
                    handler.handle(exchange);
                });
        server.start();
        System.setProperty(
                "lumira.office.worker.url", "http://127.0.0.1:" + server.getAddress().getPort());
        System.setProperty("lumira.office.worker.token", TOKEN);
    }

    @AfterEach
    void stopStubServer() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        restore("lumira.office.worker.url", oldUrl);
        restore("lumira.office.worker.token", oldToken);
    }

    @Test
    void sendsAUnicodeFilenameAsBase64AndReturnsOnlyTheProtocolPreview() {
        var response = OfficeWorkerClient.preview("合同摘要.docx", FILE);

        assertEquals("合约预览", response.text());
        assertEquals(
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString("合同摘要.docx".getBytes(StandardCharsets.UTF_8)),
                filenameHeader.get());
    }

    @Test
    void enforcesInputAndResponseCapsAndMapsTransientErrorsToExistingUnavailableReason()
            throws Exception {
        var oversizedInput =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                OfficeWorkerClient.preview(
                                        "large.docx", new byte[20 * 1024 * 1024 + 1]));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, oversizedInput.getStatusCode());
        assertEquals("filePreviewTooLarge", oversizedInput.getReason());
        assertEquals(0, requests.get());

        handler =
                exchange -> {
                    exchange.getResponseHeaders().set("Location", "/redirect-target");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                };
        var redirected =
                assertThrows(
                        ResponseStatusException.class,
                        () -> OfficeWorkerClient.preview("file.docx", FILE));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, redirected.getStatusCode());
        assertEquals("unavailable", redirected.getReason());
        assertEquals(1, requests.get());

        handler =
                exchange -> {
                    long responseLength = OfficeWorkerProtocol.MAX_RESPONSE_BYTES + 1L;
                    exchange.sendResponseHeaders(200, responseLength);
                    byte[] chunk = new byte[1024 * 1024];
                    try (var output = exchange.getResponseBody()) {
                        for (long remaining = responseLength; remaining > 0; ) {
                            int count = (int) Math.min(chunk.length, remaining);
                            output.write(chunk, 0, count);
                            remaining -= count;
                        }
                    } catch (IOException ignored) {
                        // The client should cancel after observing the bounded Content-Length.
                    }
                };
        var oversizedResponse =
                assertThrows(
                        ResponseStatusException.class,
                        () -> OfficeWorkerClient.preview("file.docx", FILE));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, oversizedResponse.getStatusCode());
        assertEquals("filePreviewTooLarge", oversizedResponse.getReason());
    }

    @Test
    void treatsUnauthorizedAndMismatchedWorkerResponsesAsUnavailable() {
        handler = exchange -> respond(exchange, 401, "unauthorized");
        var unauthorized =
                assertThrows(
                        ResponseStatusException.class,
                        () -> OfficeWorkerClient.preview("file.docx", FILE));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unauthorized.getStatusCode());
        assertEquals("unavailable", unauthorized.getReason());

        handler =
                exchange ->
                        respond(
                                exchange,
                                400,
                                "{\"kind\":\"preview\",\"preview\":null,"
                                        + "\"dictionary\":null,\"status\":503,"
                                        + "\"reason\":\"officeWorkerUnavailable\",\"issues\":[]}");
        var mismatch =
                assertThrows(
                        ResponseStatusException.class,
                        () -> OfficeWorkerClient.preview("file.docx", FILE));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, mismatch.getStatusCode());
        assertEquals("unavailable", mismatch.getReason());

        handler =
                exchange ->
                        respond(
                                exchange,
                                400,
                                "{\"kind\":\"preview\",\"preview\":null,"
                                        + "\"dictionary\":null,\"status\":400,"
                                        + "\"reason\":\"unknownWorkerError\",\"issues\":[]}");
        var unknownReason =
                assertThrows(
                        ResponseStatusException.class,
                        () -> OfficeWorkerClient.preview("file.docx", FILE));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unknownReason.getStatusCode());
        assertEquals("unavailable", unknownReason.getReason());
    }

    @Test
    void preservesDictionaryValidationErrorsAndFailsFastWhenTheSingleSlotIsBusy() throws Exception {
        handler =
                exchange ->
                        respond(
                                exchange,
                                400,
                                "{\"kind\":\"dictionary\",\"preview\":null,"
                                        + "\"dictionary\":null,\"status\":400,"
                                        + "\"reason\":\"invalidDictionaryDataset\","
                                        + "\"issues\":[{\"row\":4,\"field\":\"itemLabel\","
                                        + "\"reason\":\"invalid\"}]}");
        var invalid =
                assertThrows(
                        ImportValidationException.class,
                        () -> OfficeWorkerClient.dictionary("tree.xlsx", "TREE", FILE));
        assertEquals(4, invalid.issues().getFirst().row());

        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        handler =
                exchange -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new IOException("test release timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                    respond(exchange, 200, PREVIEW_RESPONSE);
                };
        var firstError = new AtomicReference<Throwable>();
        Thread first =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        OfficeWorkerClient.preview("first.docx", FILE);
                                    } catch (Throwable error) {
                                        firstError.set(error);
                                    }
                                });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var busy =
                assertThrows(
                        ResponseStatusException.class,
                        () -> OfficeWorkerClient.preview("second.docx", FILE));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, busy.getStatusCode());
        assertEquals("unavailable", busy.getReason());
        release.countDown();
        first.join(5000);
        assertFalse(first.isAlive());
        assertNull(firstError.get());
    }

    private void previewResponse(HttpExchange exchange) throws IOException {
        respond(exchange, 200, PREVIEW_RESPONSE);
    }

    private static void respond(HttpExchange exchange, int status, String value)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
