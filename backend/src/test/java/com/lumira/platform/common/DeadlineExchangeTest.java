package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;

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
        var resolverEntered = new CountDownLatch(1);
        var workFinished = new CountDownLatch(1);
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            try {
                // Allow class loading and worker scheduling before exercising a blocked resolver.
                HttpTimeoutException expired =
                        assertThrows(
                                HttpTimeoutException.class,
                                () ->
                                        DeadlineExchange.run(
                                                Duration.ofSeconds(2),
                                                scope -> {
                                                    try {
                                                        var socket =
                                                                scope.track(
                                                                        new GuardedSockets
                                                                                .GuardedSocket(
                                                                                address -> true,
                                                                                host -> {
                                                                                    resolverEntered
                                                                                            .countDown();
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
                                                    } finally {
                                                        workFinished.countDown();
                                                    }
                                                }));
                assertTrue(expired.getMessage().contains("timed out"));
                assertEquals(
                        0, resolverEntered.getCount(), "The blocked resolver was never exercised");
                releaseResolver.countDown();
                assertTrue(workFinished.await(5, TimeUnit.SECONDS));
                // Inspect the listening backlog only after the late worker has exited. An early
                // acceptor timeout could otherwise miss a connection dialled after it stopped.
                server.setSoTimeout(200);
                assertThrows(
                        SocketTimeoutException.class,
                        () -> {
                            try (var unexpected = server.accept()) {
                                fail("A DNS result returned after cancellation must not dial");
                            }
                        });
            } finally {
                releaseResolver.countDown();
            }
        }
    }
}
