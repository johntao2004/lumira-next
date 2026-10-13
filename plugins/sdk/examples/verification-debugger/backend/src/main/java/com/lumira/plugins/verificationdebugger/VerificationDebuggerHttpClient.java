package com.lumira.plugins.verificationdebugger;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owned loopback transport with bounded shutdown during plugin unload. */
final class VerificationDebuggerHttpClient implements AutoCloseable {
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .proxy(
                            new ProxySelector() {
                                @Override
                                public List<Proxy> select(URI uri) {
                                    return List.of(Proxy.NO_PROXY);
                                }

                                @Override
                                public void connectFailed(
                                        URI uri, SocketAddress address, IOException failure) {}
                            })
                    .build();
    private final AtomicBoolean closed = new AtomicBoolean();

    HttpResponse<byte[]> send(HttpRequest request, HttpResponse.BodyHandler<byte[]> bodyHandler)
            throws IOException, InterruptedException {
        return client.send(request, bodyHandler);
    }

    boolean isTerminated() {
        return client.isTerminated();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        client.shutdown();
        try {
            if (!client.awaitTermination(CLOSE_TIMEOUT)) client.shutdownNow();
        } catch (InterruptedException interrupted) {
            client.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
