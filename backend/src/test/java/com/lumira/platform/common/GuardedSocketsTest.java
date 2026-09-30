package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardedSocketsTest {
    private HttpsServer server;
    private SSLContext context;
    private volatile String seenHost;

    @BeforeEach
    void start(@TempDir Path directory) throws Exception {
        Path store = directory.resolve("test.p12");
        var keytool =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "keytool")
                                        .toString(),
                                "-genkeypair",
                                "-alias",
                                "t",
                                "-keyalg",
                                "RSA",
                                "-keysize",
                                "2048",
                                "-validity",
                                "2",
                                "-storetype",
                                "PKCS12",
                                "-keystore",
                                store.toString(),
                                "-storepass",
                                "changeit",
                                "-dname",
                                "CN=guarded.test",
                                "-ext",
                                "san=dns:guarded.test,dns:localhost")
                        .redirectErrorStream(true)
                        .start();
        keytool.getInputStream().readAllBytes();
        assertEquals(0, keytool.waitFor());
        var keys = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) {
            keys.load(in, "changeit".toCharArray());
        }
        var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, "changeit".toCharArray());
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keys);
        context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trust.getTrustManagers(), null);
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext(
                "/echo",
                exchange -> {
                    seenHost = exchange.getRequestHeaders().getFirst("Host");
                    byte[] request = exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(200, request.length);
                    exchange.getResponseBody().write(request);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void layersTlsOverTheGuardedSocketUsingTheOriginalHostnameForSniAndVerification()
            throws Exception {
        var factory =
                new GuardedSockets.Factory(
                        context.getSocketFactory(), address -> address.isLoopbackAddress());
        var socket = factory.createSocket();
        socket.connect(
                new InetSocketAddress(
                        InetAddress.getLoopbackAddress(), server.getAddress().getPort()),
                2000);
        var tls =
                (javax.net.ssl.SSLSocket)
                        factory.createSocket(
                                socket, "guarded.test", server.getAddress().getPort(), true);
        var parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        tls.setSSLParameters(parameters);
        tls.startHandshake();
        assertEquals(
                "guarded.test",
                ((javax.net.ssl.ExtendedSSLSession) tls.getSession()).getPeerHost());
        tls.close();
    }

    @Test
    void guardedSocketRefusesAddressesThePolicyForbids() throws Exception {
        var factory = new GuardedSockets.Factory(context.getSocketFactory(), address -> false);
        var socket = factory.createSocket();
        var refused =
                assertThrows(
                        IOException.class,
                        () ->
                                socket.connect(
                                        new InetSocketAddress(
                                                InetAddress.getLoopbackAddress(),
                                                server.getAddress().getPort()),
                                        2000));
        assertEquals("Destination not permitted", refused.getMessage());
        assertThrows(
                IOException.class,
                () -> factory.createSocket("localhost", server.getAddress().getPort()));
    }

    @Test
    void exchangeCompletesARoundTripWhenThePolicyAllowsTheDestination() throws Exception {
        var uri = URI.create("https://localhost:" + server.getAddress().getPort() + "/echo");
        var response =
                GuardedSockets.exchange(
                        uri,
                        "POST",
                        Map.of("Content-Type", "text/plain"),
                        "ping".getBytes(StandardCharsets.UTF_8),
                        Duration.ofSeconds(3),
                        1024,
                        context.getSocketFactory(),
                        address -> address.isLoopbackAddress());
        assertEquals(200, response.status());
        assertEquals("ping", new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("localhost:" + server.getAddress().getPort(), seenHost);
    }

    @Test
    void pinnedExchangeUsesTheConfiguredAddressAndOriginalHostnameForTls() throws Exception {
        var uri = URI.create("https://guarded.test:" + server.getAddress().getPort() + "/echo");
        var response =
                GuardedSockets.exchangePinned(
                        uri,
                        "POST",
                        Map.of("Content-Type", "text/plain"),
                        "pin".getBytes(StandardCharsets.UTF_8),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        1024,
                        context.getSocketFactory(),
                        InetAddress::isLoopbackAddress,
                        host -> {
                            assertEquals("guarded.test", host);
                            return new InetAddress[] {InetAddress.getLoopbackAddress()};
                        });
        assertEquals(200, response.status());
        assertEquals("pin", new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("guarded.test:" + server.getAddress().getPort(), seenHost);
    }

    @Test
    void pinnedExchangeRejectsAllAnswersBeforeDialingIfAnyIsForbidden() throws Exception {
        var uri = URI.create("https://guarded.test:" + server.getAddress().getPort() + "/echo");
        var refused =
                assertThrows(
                        IOException.class,
                        () ->
                                GuardedSockets.exchangePinned(
                                        uri,
                                        "GET",
                                        Map.of(),
                                        null,
                                        Duration.ofSeconds(2),
                                        Duration.ofSeconds(2),
                                        1024,
                                        context.getSocketFactory(),
                                        InetAddress::isLoopbackAddress,
                                        host ->
                                                new InetAddress[] {
                                                    InetAddress.getLoopbackAddress(),
                                                    InetAddress.getByName("8.8.8.8")
                                                }));
        assertEquals("Destination not permitted", rootMessage(refused));
        assertEquals(null, seenHost);
    }

    @Test
    void exchangeRefusesTheDestinationWhenThePolicyForbidsIt() {
        var uri = URI.create("https://localhost:" + server.getAddress().getPort() + "/echo");
        var refused =
                assertThrows(
                        IOException.class,
                        () ->
                                GuardedSockets.exchange(
                                        uri,
                                        "POST",
                                        Map.of(),
                                        "x".getBytes(StandardCharsets.UTF_8),
                                        Duration.ofSeconds(3),
                                        1024,
                                        context.getSocketFactory(),
                                        address -> false));
        assertEquals("Destination not permitted", rootMessage(refused));
        assertEquals(null, seenHost);
    }

    @Test
    void publicPolicyBlocksLoopbackAndPrivateTargetsBeforeAnyBytesAreSent() {
        var uri = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/echo");
        var refused =
                assertThrows(
                        IOException.class,
                        () ->
                                GuardedSockets.exchange(
                                        uri, "GET", Map.of(), null, Duration.ofSeconds(3), 1024));
        assertEquals("Destination not permitted", rootMessage(refused));
        assertEquals(null, seenHost);
    }

    @Test
    void refusesNonHttpsDestinations() {
        assertThrows(
                IOException.class,
                () ->
                        GuardedSockets.exchange(
                                URI.create("http://8.8.8.8/"),
                                "GET",
                                Map.of(),
                                null,
                                Duration.ofSeconds(1),
                                16));
    }

    private static String rootMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage();
    }
}
