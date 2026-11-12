package com.lumira.platform.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/** A bounded, lazily loaded value; failed loads never replace a valid snapshot. */
public final class ExpiringValue<T> {
    private record Entry<T>(T value, Instant expiresAt) {}

    private final Duration ttl;
    private final Supplier<T> loader;
    private final Clock clock;
    private volatile Entry<T> entry;

    public ExpiringValue(Duration ttl, Supplier<T> loader) {
        this(ttl, loader, Clock.systemUTC());
    }

    public ExpiringValue(Duration ttl, Supplier<T> loader, Clock clock) {
        if (ttl.isNegative() || ttl.isZero())
            throw new IllegalArgumentException("positive TTL required");
        this.ttl = ttl;
        this.loader = Objects.requireNonNull(loader);
        this.clock = Objects.requireNonNull(clock);
    }

    public T get() {
        Entry<T> snapshot = entry;
        if (fresh(snapshot)) return snapshot.value();
        synchronized (this) {
            snapshot = entry;
            return fresh(snapshot) ? snapshot.value() : refresh();
        }
    }

    public synchronized T refresh() {
        T value = Objects.requireNonNull(loader.get());
        put(value);
        return value;
    }

    public synchronized void put(T value) {
        entry = new Entry<>(Objects.requireNonNull(value), clock.instant().plus(ttl));
    }

    public synchronized void invalidate() {
        entry = null;
    }

    private boolean fresh(Entry<T> snapshot) {
        return snapshot != null && clock.instant().isBefore(snapshot.expiresAt());
    }
}
