package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExpiringValueTest {
    @Test
    void expiresAtBoundaryAndPublishedSaveReplacesCachedRead() {
        var time = new TestClock();
        var loads = new AtomicInteger();
        var value = new ExpiringValue<>(Duration.ofSeconds(2), loads::incrementAndGet, time);
        assertEquals(1, value.get());
        time.now.set(time.instant().plusMillis(1999));
        assertEquals(1, value.get());
        time.now.set(time.instant().plusMillis(1));
        assertEquals(2, value.get());
        value.put(90);
        assertEquals(90, value.get());
        assertEquals(2, loads.get());
        assertEquals(3, value.refresh());
    }

    @Test
    void failedRefreshRetainsUnexpiredValueAndExpiredFailureRetries() {
        var time = new TestClock();
        var next = new AtomicReference<Integer>(1);
        var value =
                new ExpiringValue<>(
                        Duration.ofSeconds(2),
                        () -> {
                            Integer result = next.get();
                            if (result == null) throw new IllegalStateException("load failed");
                            return result;
                        },
                        time);
        assertEquals(1, value.get());
        next.set(null);
        assertThrows(IllegalStateException.class, value::refresh);
        assertEquals(1, value.get());
        time.now.set(time.instant().plusSeconds(2));
        assertThrows(IllegalStateException.class, value::get);
        next.set(2);
        assertEquals(2, value.get());
    }

    @Test
    void concurrentColdReadsShareOneLoad() throws Exception {
        var loads = new AtomicInteger();
        var value =
                new ExpiringValue<>(Duration.ofSeconds(2), loads::incrementAndGet, new TestClock());
        try (var executor = Executors.newFixedThreadPool(12)) {
            var results = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 24; i++) results.add(executor.submit(value::get));
            for (var result : results) assertEquals(1, result.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, loads.get());
    }

    private static final class TestClock extends Clock {
        final AtomicReference<Instant> now =
                new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
