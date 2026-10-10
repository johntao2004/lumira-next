package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.RateLimitExceededException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AccountCredentialPreflightTest {
    private static final String PASSWORD = "QA-only-password";
    private static final String HASH = "encoded-password-hash";

    @Test
    void emailAndPhonePreflightsShareTwoSlotsAndRejectThirdConcurrentHash() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.matches(PASSWORD, HASH))
                .thenAnswer(
                        invocation -> {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("password hash test timed out");
                            return true;
                        });

        ExecutorService workers = Executors.newFixedThreadPool(2);
        List<Future<?>> active = new ArrayList<>();
        try {
            active.add(
                    workers.submit(
                            () ->
                                    AccountCredentialPreflight.requireCurrentPassword(
                                            encoder, PASSWORD, HASH))); // AccountEmailService path
            active.add(
                    workers.submit(
                            () ->
                                    AccountCredentialPreflight.requireCurrentPassword(
                                            encoder, PASSWORD, HASH))); // AccountPhoneService path
            assertTrue(entered.await(3, TimeUnit.SECONDS), "both Argon2 slots should be occupied");

            RateLimitExceededException limited =
                    assertThrows(
                            RateLimitExceededException.class,
                            () ->
                                    AccountCredentialPreflight.requireCurrentPassword(
                                            encoder, PASSWORD, HASH));
            assertEquals("passwordHashCapacity", limited.getMessage());

            release.countDown();
            for (Future<?> task : active) task.get(3, TimeUnit.SECONDS);
            assertSlotsAvailable();
        } finally {
            release.countDown();
            workers.shutdownNow();
            workers.awaitTermination(3, TimeUnit.SECONDS);
            drainAvailableSlots();
        }
    }

    @Test
    void passwordEncoderFailureAlwaysReleasesBothSlots() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.matches(PASSWORD, HASH)).thenThrow(new IllegalStateException("test failure"));

        assertThrows(
                IllegalStateException.class,
                () -> AccountCredentialPreflight.requireCurrentPassword(encoder, PASSWORD, HASH));
        assertSlotsAvailable();
    }

    private static void assertSlotsAvailable() {
        int acquired = 0;
        try {
            assertTrue(PasswordHashCapacity.tryAcquire());
            acquired++;
            assertTrue(PasswordHashCapacity.tryAcquire());
            acquired++;
            assertFalse(PasswordHashCapacity.tryAcquire());
        } finally {
            while (acquired-- > 0) PasswordHashCapacity.release();
        }
    }

    private static void drainAvailableSlots() {
        int acquired = 0;
        while (acquired < 2 && PasswordHashCapacity.tryAcquire()) acquired++;
        while (acquired-- > 0) PasswordHashCapacity.release();
    }
}
