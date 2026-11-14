package com.lumira.platform.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.audit.AuditRepository;
import com.lumira.platform.common.MailConfigurationPort;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MailDeliveryWorkerTest {
    MailDeliveryStore store = mock(MailDeliveryStore.class);
    SmtpConnectionSource source = mock(SmtpConnectionSource.class);
    SmtpGateway gateway = mock(SmtpGateway.class);
    AuditRepository audit = mock(AuditRepository.class);
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    MailDeliveryWorker worker;
    MailDeliveryStore.Claim claim =
            new MailDeliveryStore.Claim(UUID.randomUUID(), UUID.randomUUID(), 1);
    OutgoingMail message = new OutgoingMail("fixture@example.test", "Fixture", "Body");
    byte[] password = {1, 2, 3};
    SmtpConnection connection;

    @BeforeEach
    void setup() {
        when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        worker = new MailDeliveryWorker(store, source, gateway, audit, manager);
        connection =
                new SmtpConnection(
                        new MailConfigurationPort.Settings(
                                true,
                                "localhost",
                                587,
                                "STARTTLS",
                                true,
                                "sender",
                                "sender@example.test",
                                ""),
                        password);
        when(store.message(claim)).thenReturn(Optional.of(message));
        when(source.load()).thenReturn(connection);
        when(store.beginSending(claim)).thenReturn(true);
        when(store.complete(eq(claim), any())).thenReturn(true);
    }

    @AfterEach
    void close() {
        worker.close();
    }

    @Test
    void sendsOnlyAfterStartAndRecordsOutcome() {
        when(gateway.send(connection, message)).thenReturn(SmtpGateway.Result.ACCEPTED);
        worker.process(claim, 1000);
        var order = inOrder(store, audit, gateway, manager);
        order.verify(store).beginSending(claim);
        order.verify(audit).append("system:mail", "mail.started", claim.id());
        order.verify(manager).commit(any());
        order.verify(gateway).send(connection, message);
        order.verify(store).complete(claim, SmtpGateway.Result.ACCEPTED);
        order.verify(audit).append("system:mail", "mail.ACCEPTED", claim.id());
        assertArrayEquals(new byte[3], password);
    }

    @Test
    void expiredLeaseNeverSendsAndClosesLoadedCredential() {
        when(store.beginSending(claim)).thenReturn(false);
        worker.process(claim, 1000);
        verifyNoInteractions(gateway);
        verify(store, never()).complete(any(), any());
        assertArrayEquals(new byte[3], password);
    }

    @Test
    void missingConfigurationFailsBeforeNetwork() {
        when(source.load()).thenThrow(new IllegalStateException("sensitive detail"));
        when(store.failBeforeSending(claim)).thenReturn(true);
        worker.process(claim, 1000);
        verifyNoInteractions(gateway);
        verify(store).failBeforeSending(claim);
        verify(store, never()).beginSending(any());
    }

    @Test
    void unexpectedTransportFailureIsUncertain() {
        when(gateway.send(connection, message))
                .thenThrow(new IllegalStateException("sensitive server response"));
        worker.process(claim, 1000);
        verify(store).complete(claim, SmtpGateway.Result.DELIVERY_UNCERTAIN);
        assertArrayEquals(new byte[3], password);
    }

    @Test
    void deadlineInterruptsAttemptAndOverridesConnectionFailure() throws Exception {
        when(gateway.send(connection, message))
                .thenAnswer(
                        i -> {
                            try {
                                Thread.sleep(10000);
                            } catch (InterruptedException expected) {
                                return SmtpGateway.Result.CONNECTION_FAILED;
                            }
                            return SmtpGateway.Result.ACCEPTED;
                        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(
                            () -> {
                                worker.process(claim, 50);
                                assertFalse(Thread.currentThread().isInterrupted());
                            })
                    .get(3, TimeUnit.SECONDS);
        }
        verify(store).complete(claim, SmtpGateway.Result.DELIVERY_UNCERTAIN);
        assertArrayEquals(new byte[3], password);
    }

    @Test
    void blockedRenewalPoolCannotDelayNetworkDeadline() throws Exception {
        var field =
                com.lumira.platform.communication.DurableDeliveryWorker.class.getDeclaredField(
                        "leaseSchedules");
        field.setAccessible(true);
        var renewals = (ScheduledExecutorService) field.get(worker);
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        for (int i = 0; i < 4; i++)
            renewals.submit(
                    () -> {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            when(gateway.send(connection, message))
                    .thenAnswer(
                            invocation -> {
                                try {
                                    Thread.sleep(10000);
                                } catch (InterruptedException interrupted) {
                                    return SmtpGateway.Result.CONNECTION_FAILED;
                                }
                                return SmtpGateway.Result.ACCEPTED;
                            });
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(() -> worker.process(claim, 50)).get(2, TimeUnit.SECONDS);
            }
            verify(store).complete(claim, SmtpGateway.Result.DELIVERY_UNCERTAIN);
        } finally {
            release.countDown();
        }
    }

    @Test
    void lateRenewalFailureDoesNotInterruptCompletion() throws Exception {
        var field =
                com.lumira.platform.communication.DurableDeliveryWorker.class.getDeclaredField(
                        "leaseSchedules");
        field.setAccessible(true);
        var renewals = (ScheduledThreadPoolExecutor) field.get(worker);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        when(store.renewLease(claim))
                .thenAnswer(
                        invocation -> {
                            entered.countDown();
                            assertTrue(release.await(3, TimeUnit.SECONDS));
                            return false;
                        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            when(gateway.send(connection, message))
                    .thenAnswer(
                            invocation -> {
                                Runnable scheduled = renewals.getQueue().peek();
                                assertNotNull(scheduled);
                                executor.submit(scheduled);
                                assertTrue(entered.await(2, TimeUnit.SECONDS));
                                return SmtpGateway.Result.ACCEPTED;
                            });
            var result =
                    executor.submit(
                            () -> {
                                worker.process(claim, 1000);
                                completed.countDown();
                                try {
                                    Thread.sleep(250);
                                } catch (InterruptedException failure) {
                                    throw new AssertionError(
                                            "Late renewal interrupted completed attempt", failure);
                                }
                            });
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            release.countDown();
            result.get(2, TimeUnit.SECONDS);
            verify(store).complete(claim, SmtpGateway.Result.ACCEPTED);
        } finally {
            release.countDown();
        }
    }

    @Test
    void completionFailureDoesNotFallBackToPreSendFailure() {
        when(gateway.send(connection, message)).thenReturn(SmtpGateway.Result.ACCEPTED);
        when(store.complete(claim, SmtpGateway.Result.ACCEPTED))
                .thenThrow(new IllegalStateException("Database unavailable"));
        worker.process(claim, 1000);
        verify(store, never()).failBeforeSending(any());
        assertArrayEquals(new byte[3], password);
    }

    @Test
    void networkDeadlineActuallyClosesAVirtualThreadSocket() throws Exception {
        worker.close();
        worker = new MailDeliveryWorker(store, source, new SmtpGateway(), audit, manager);
        try (var server =
                        new java.net.ServerSocket(
                                0, 1, java.net.InetAddress.getByName("127.0.0.1"));
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setSoTimeout(10000);
            connection =
                    new SmtpConnection(
                            new MailConfigurationPort.Settings(
                                    true,
                                    "127.0.0.1",
                                    server.getLocalPort(),
                                    "STARTTLS",
                                    true,
                                    "sender",
                                    "sender@example.test",
                                    ""),
                            password);
            when(source.load()).thenReturn(connection);
            var peer =
                    tasks.submit(
                            () -> {
                                try (var socket = server.accept()) {
                                    socket.setSoTimeout(10000);
                                    var writer =
                                            new java.io.PrintWriter(socket.getOutputStream(), true);
                                    writer.print("220 localhost ESMTP\r\n");
                                    writer.flush();
                                    var reader =
                                            new java.io.BufferedReader(
                                                    new java.io.InputStreamReader(
                                                            socket.getInputStream(),
                                                            java.nio.charset.StandardCharsets
                                                                    .US_ASCII));
                                    assertTrue(reader.readLine().startsWith("EHLO "));
                                    // Do not answer EHLO: the worker deadline must close this
                                    // waiting socket.
                                    return reader.readLine() == null;
                                }
                            });
            tasks.submit(() -> worker.process(claim, 2000)).get(10, TimeUnit.SECONDS);
            assertTrue(peer.get(10, TimeUnit.SECONDS));
        }
        verify(store).complete(claim, SmtpGateway.Result.DELIVERY_UNCERTAIN);
        assertArrayEquals(new byte[3], password);
    }

    @Test
    void pollNeverExceedsFourConcurrentAttempts() throws Exception {
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(4);
        when(store.claim()).thenReturn(Optional.of(claim));
        // Each active attempt owns its own credential object.
        when(source.load())
                .thenAnswer(
                        i ->
                                new SmtpConnection(
                                        new MailConfigurationPort.Settings(
                                                true,
                                                "localhost",
                                                587,
                                                "STARTTLS",
                                                true,
                                                "sender",
                                                "sender@example.test",
                                                ""),
                                        new byte[] {1}));
        when(gateway.send(any(), eq(message)))
                .thenAnswer(
                        i -> {
                            entered.countDown();
                            try {
                                release.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                return SmtpGateway.Result.DELIVERY_UNCERTAIN;
                            }
                            return SmtpGateway.Result.ACCEPTED;
                        });
        when(store.complete(eq(claim), any()))
                .thenAnswer(
                        i -> {
                            finished.countDown();
                            return true;
                        });
        try {
            worker.poll();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            worker.poll();
            verify(store, times(4)).claim();
        } finally {
            release.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS));
        }
    }
}
