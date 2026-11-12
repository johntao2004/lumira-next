package com.lumira.platform.common;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A writer reads its own transaction without publishing uncommitted values to other requests. */
public final class TransactionCachedValue<T> {
    private final Supplier<T> loader;
    private final ExpiringValue<T> cache;

    public TransactionCachedValue(Duration ttl, Supplier<T> loader) {
        this.loader = Objects.requireNonNull(loader);
        this.cache = new ExpiringValue<>(ttl, loader);
    }

    public T get() {
        return writerTransaction() ? Objects.requireNonNull(loader.get()) : cache.get();
    }

    public T refresh() {
        return writerTransaction() ? Objects.requireNonNull(loader.get()) : cache.refresh();
    }

    public void invalidateAfterCommit() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            cache.invalidate();
                        }
                    });
        } else cache.invalidate();
    }

    private boolean writerTransaction() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }
}
