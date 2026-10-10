package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DeadlineExchangeTest {
    @Test
    void deadlineClosesTrackedResourcesAndCancelsTheWork() {
        var closed = new AtomicBoolean();

        HttpTimeoutException expired =
                assertThrows(
                        HttpTimeoutException.class,
                        () ->
                                DeadlineExchange.run(
                                        Duration.ofMillis(80),
                                        scope -> {
                                            scope.track(() -> closed.set(true));
                                            while (true) Thread.sleep(10);
                                        }));

        assertTrue(expired.getMessage().contains("timed out"));
        assertTrue(closed.get());
    }

    @Test
    void workerSaturationFailsImmediatelyWithoutQueueing() throws Exception {
        var entered = new CountDownLatch(32);
        var release = new CountDownLatch(1);
        try (var callers = Executors.newFixedThreadPool(33)) {
            var work = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 32; index++) {
                work.add(
                        callers.submit(
                                () ->
                                        DeadlineExchange.run(
                                                Duration.ofSeconds(5),
                                                scope -> {
                                                    entered.countDown();
                                                    release.await();
                                                    return null;
                                                })));
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(
                    DeadlineExchange.Busy.class,
                    () -> DeadlineExchange.run(Duration.ofSeconds(1), scope -> null));
            release.countDown();
            for (var result : work) result.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void aDnsResolutionThatReturnsAfterTheDeadlineCannotDial() throws Exception {
        var releaseResolver = new CountDownLatch(1);
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(150);
            var resolverFinished = new CountDownLatch(1);
            var accepted = new AtomicBoolean();
            var acceptor =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try (var connection = server.accept()) {
                                            accepted.set(true);
                                        } catch (SocketTimeoutException expected) {
                                            // No late connection arrived.
                                        } catch (IOException failure) {
                                            throw new AssertionError(failure);
                                        }
                                    });

            try {
                HttpTimeoutException expired =
                        assertThrows(
                                HttpTimeoutException.class,
                                () ->
                                        DeadlineExchange.run(
                                                Duration.ofMillis(80),
                                                scope -> {
                                                    var socket =
                                                            scope.track(
                                                                    new GuardedSockets
                                                                            .GuardedSocket(
                                                                            address -> true,
                                                                            host -> {
                                                                                boolean
                                                                                        interrupted =
                                                                                                false;
                                                                                while (true) {
                                                                                    try {
                                                                                        releaseResolver
                                                                                                .await();
                                                                                        break;
                                                                                    } catch (
                                                                                            InterruptedException
                                                                                                    ignored) {
                                                                                        interrupted =
                                                                                                true;
                                                                                    }
                                                                                }
                                                                                resolverFinished
                                                                                        .countDown();
                                                                                if (interrupted)
                                                                                    Thread
                                                                                            .currentThread()
                                                                                            .interrupt();
                                                                                return new InetAddress
                                                                                        [] {
                                                                                    InetAddress
                                                                                            .getLoopbackAddress()
                                                                                };
                                                                            },
                                                                            scope));
                                                    socket.connect(
                                                            InetSocketAddress.createUnresolved(
                                                                    "late.example",
                                                                    server.getLocalPort()),
                                                            1000);
                                                    return null;
                                                }));

                assertTrue(expired.getMessage().contains("timed out"));
                releaseResolver.countDown();
                assertTrue(resolverFinished.await(1, TimeUnit.SECONDS));
                acceptor.join(500);
                assertFalse(accepted.get());
            } finally {
                releaseResolver.countDown();
            }
        }
    }
}
