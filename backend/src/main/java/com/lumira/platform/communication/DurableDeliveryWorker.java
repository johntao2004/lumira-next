/**
 * 以有界并发、续租和截止时间执行渠道投递，网络调用不占数据库事务，结果与审计另行提交，超时或失租按不确定处理。 Executes provider deliveries with bounded
 * concurrency, lease renewal and deadlines outside database transactions, committing outcomes with
 * audits and treating timeout or lease loss as uncertain.
 */
package com.lumira.platform.communication;

import com.lumira.platform.common.AuditPort;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 持久化服务商投递的共享编排；通道模块只提供载荷、连接、网关结果与存储适配器，领取隔离、有界并发、租约续期、截止中断及保守完成语义由同一实现负责。
 *
 * <p>Shared orchestration for durable provider deliveries.
 *
 * <p>Channel modules provide only their payload, connection, gateway result and store adapter.
 * Claim fencing, bounded concurrency, lease renewal, deadline interruption and conservative
 * completion semantics remain one implementation.
 */
public abstract class DurableDeliveryWorker<P, C extends AutoCloseable, R>
        implements AutoCloseable {
    @FunctionalInterface
    public interface ConnectionSource<C> {
        C load();
    }

    @FunctionalInterface
    public interface Sender<C, P, R> {
        R send(C connection, P payload);
    }

    private static final int MAX_CONCURRENCY = 4;
    private static final long LEASE_RENEWAL_SECONDS = 30;
    private static final long LEASE_RENEWAL_PERIOD_SECONDS = 30;

    private final Logger log;
    private final DurableDeliveryStore<P> store;
    private final ConnectionSource<C> source;
    private final Sender<C, P, R> sender;
    private final BiFunction<DurableDeliveryStore.Claim, R, Boolean> complete;
    private final Function<R, String> resultName;
    private final Supplier<List<DurableDeliveryStore.Recovery>> recoverExpired;
    private final Supplier<R> uncertainResult;
    private final AuditPort audit;
    private final TransactionTemplate transaction;
    private final String channel;
    private final long defaultTimeoutMillis;
    private final ExecutorService workers;
    private final ScheduledExecutorService schedules;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENCY);
    private final AtomicBoolean stopped = new AtomicBoolean();

    protected DurableDeliveryWorker(
            DurableDeliveryStore<P> store,
            ConnectionSource<C> source,
            Sender<C, P, R> sender,
            BiFunction<DurableDeliveryStore.Claim, R, Boolean> complete,
            Function<R, String> resultName,
            Supplier<List<DurableDeliveryStore.Recovery>> recoverExpired,
            Supplier<R> uncertainResult,
            AuditPort audit,
            PlatformTransactionManager manager,
            String channel,
            long defaultTimeoutMillis) {
        this.store = store;
        this.source = source;
        this.sender = sender;
        this.complete = complete;
        this.resultName = resultName;
        this.recoverExpired = recoverExpired;
        this.uncertainResult = uncertainResult;
        this.audit = audit;
        this.transaction = new TransactionTemplate(manager);
        this.channel = channel;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
        this.log = LoggerFactory.getLogger(getClass());
        this.workers =
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name(channel + "-delivery-", 0).factory());
        this.schedules =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform()
                                .daemon()
                                .name(channel + "-delivery-schedules")
                                .factory());
    }

    protected void pollQueue() {
        if (stopped.get()) return;
        try {
            transaction.executeWithoutResult(
                    status -> {
                        for (var row : recoverExpired.get()) {
                            audit.append(
                                    "system:" + channel, channel + "." + row.state(), row.id());
                        }
                    });
            for (int batch = 0;
                    batch < MAX_CONCURRENCY && !stopped.get() && slots.tryAcquire();
                    batch++) {
                boolean submitted = false;
                try {
                    Optional<DurableDeliveryStore.Claim> claim = store.claim();
                    if (claim.isEmpty()) return;
                    workers.execute(
                            () -> {
                                try {
                                    processQueue(claim.get(), defaultTimeoutMillis);
                                } finally {
                                    slots.release();
                                }
                            });
                    submitted = true;
                } finally {
                    if (!submitted) slots.release();
                }
            }
        } catch (Exception error) {
            log.warn("{} queue polling deferred ({})", channel, error.getClass().getSimpleName());
        }
    }

    protected void processQueue(DurableDeliveryStore.Claim claim) {
        processQueue(claim, defaultTimeoutMillis);
    }

    protected void processQueue(DurableDeliveryStore.Claim claim, long timeoutMillis) {
        boolean started = false;
        try {
            var message = store.message(claim);
            if (message.isEmpty()) return;
            try (C connection = source.load()) {
                started =
                        Boolean.TRUE.equals(
                                transaction.execute(
                                        status -> {
                                            if (!store.beginSending(claim)) return false;
                                            audit.append(
                                                    "system:" + channel,
                                                    channel + ".started",
                                                    claim.id());
                                            return true;
                                        }));
                if (!started) return;

                Thread current = Thread.currentThread();
                AtomicBoolean leaseLost = new AtomicBoolean();
                var leaseRenewal =
                        schedules.scheduleAtFixedRate(
                                () -> {
                                    try {
                                        if (!store.renewLease(claim)) {
                                            leaseLost.set(true);
                                            current.interrupt();
                                        }
                                    } catch (RuntimeException error) {
                                        leaseLost.set(true);
                                        current.interrupt();
                                    }
                                },
                                LEASE_RENEWAL_SECONDS,
                                LEASE_RENEWAL_PERIOD_SECONDS,
                                TimeUnit.SECONDS);

                R result;
                boolean[] deadlineState = {false, false};
                Object deadlineLock = new Object();
                ScheduledFuture<?> deadline =
                        timeoutMillis > 0
                                ? schedules.schedule(
                                        () -> {
                                            synchronized (deadlineLock) {
                                                if (!deadlineState[0]) {
                                                    deadlineState[1] = true;
                                                    current.interrupt();
                                                }
                                            }
                                        },
                                        timeoutMillis,
                                        TimeUnit.MILLISECONDS)
                                : null;
                try {
                    try {
                        result = sender.send(connection, message.get());
                    } catch (RuntimeException error) {
                        result = uncertainResult.get();
                    }
                } finally {
                    leaseRenewal.cancel(false);
                    synchronized (deadlineLock) {
                        deadlineState[0] = true;
                        if (deadlineState[1]) Thread.interrupted();
                    }
                    if (deadline != null) deadline.cancel(false);
                }
                if (deadlineState[1] || leaseLost.get()) {
                    if (leaseLost.get()) Thread.interrupted();
                    result = uncertainResult.get();
                }
                R outcome = result;
                transaction.executeWithoutResult(
                        status -> {
                            if (Boolean.TRUE.equals(complete.apply(claim, outcome))) {
                                audit.append(
                                        "system:" + channel,
                                        channel + "." + resultName.apply(outcome),
                                        claim.id());
                            }
                        });
            }
        } catch (Exception error) {
            if (!started) {
                try {
                    transaction.executeWithoutResult(
                            status -> {
                                if (store.failBeforeSending(claim)) {
                                    audit.append(
                                            "system:" + channel,
                                            channel + ".configurationUnavailable",
                                            claim.id());
                                }
                            });
                } catch (RuntimeException failure) {
                    log.warn("{} failure recording deferred for {}", channel, claim.id());
                }
            }
            // 完成事务失败时保留 SENDING，等待保守的租约过期恢复。
            // A failed completion transaction leaves SENDING for conservative expiry recovery.
            log.warn(
                    "{} attempt deferred for {} ({})",
                    channel,
                    claim.id(),
                    error.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        if (stopped.compareAndSet(false, true)) {
            workers.shutdownNow();
            schedules.shutdownNow();
        }
    }
}
