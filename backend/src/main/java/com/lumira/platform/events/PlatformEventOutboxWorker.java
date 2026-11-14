/**
 * 以有界并发将消费者数据库副作用与租约校验后的确认放在同一事务，过期领取使两者回滚，外部网络副作用不在此原子边界内。 Consumes with bounded concurrency and
 * commits database effects with a lease-fenced acknowledgement in one transaction, rolling both
 * back for stale claims while excluding external network effects from this atomic boundary.
 */
package com.lumira.platform.events;

import com.lumira.platform.common.DurableEventConsumerPort;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 有界且支持横向并发的 Outbox 投递；PostgreSQL SKIP LOCKED
 * 确立领取权，消费者数据库副作用与令牌隔离的确认同属一个事务，过期租约会使两者一并回滚；外部网络副作用必须经独立持久化 Outbox 发出。
 *
 * <p>Bounded, horizontally-safe Outbox delivery. PostgreSQL SKIP LOCKED owns the claim. Consumer
 * database effects and the token-fenced acknowledgement share one transaction, so a stale lease
 * rolls both back. External network effects must be emitted through a separate durable outbox.
 */
@Component
public final class PlatformEventOutboxWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PlatformEventOutboxWorker.class);
    private static final int MAX_IN_FLIGHT = 8;

    private final PlatformEventOutboxStore store;
    private final PlatformEventConsumerRegistry registry;
    private final TransactionTemplate transaction;
    private final ExecutorService workers =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("platform-event-", 0).factory());
    private final int concurrency;
    private final Semaphore slots;
    private final AtomicBoolean stopped = new AtomicBoolean();

    @Autowired
    public PlatformEventOutboxWorker(
            PlatformEventOutboxStore store,
            PlatformEventConsumerRegistry registry,
            PlatformTransactionManager transactionManager,
            @Value("${lumira.events.concurrency:8}") int concurrency) {
        this(store, registry, new TransactionTemplate(transactionManager), concurrency);
    }

    public PlatformEventOutboxWorker(
            PlatformEventOutboxStore store,
            PlatformEventConsumerRegistry registry,
            PlatformTransactionManager transactionManager) {
        this(store, registry, new TransactionTemplate(transactionManager));
    }

    /**
     * 包内构造器，供不使用数据库事务的隔离单元测试使用。 Package-private constructor used by isolated unit tests without a
     * database transaction.
     */
    PlatformEventOutboxWorker(
            PlatformEventOutboxStore store, PlatformEventConsumerRegistry registry) {
        this(store, registry, (TransactionTemplate) null);
    }

    PlatformEventOutboxWorker(
            PlatformEventOutboxStore store,
            PlatformEventConsumerRegistry registry,
            TransactionTemplate transaction) {
        this(store, registry, transaction, MAX_IN_FLIGHT);
    }

    PlatformEventOutboxWorker(
            PlatformEventOutboxStore store,
            PlatformEventConsumerRegistry registry,
            TransactionTemplate transaction,
            int concurrency) {
        if (concurrency < 1 || concurrency > 64)
            throw new IllegalArgumentException("Invalid event worker concurrency");
        this.store = store;
        this.registry = registry;
        this.transaction = transaction;
        this.concurrency = concurrency;
        this.slots = new Semaphore(concurrency);
    }

    @Scheduled(
            fixedDelayString = "${lumira.events.poll-interval-ms:2000}",
            initialDelayString = "${lumira.events.poll-initial-delay-ms:15000}")
    public void poll() {
        if (stopped.get()) return;
        for (int i = 0; i < concurrency && !stopped.get() && slots.tryAcquire(); i++) {
            boolean submitted = false;
            try {
                var claim = store.claim();
                if (claim.isEmpty()) return;
                workers.execute(
                        () -> {
                            try {
                                var next = claim;
                                while (!stopped.get() && next.isPresent()) {
                                    process(next.get());
                                    if (stopped.get()) break;
                                    next = store.claim();
                                }
                            } catch (RuntimeException failure) {
                                log.warn(
                                        "Platform event drain deferred ({})",
                                        failure.getClass().getSimpleName());
                            } finally {
                                slots.release();
                            }
                        });
                submitted = true;
            } catch (RuntimeException failure) {
                log.warn(
                        "Platform event polling deferred ({})", failure.getClass().getSimpleName());
            } finally {
                if (!submitted) slots.release();
            }
        }
    }

    void process(PlatformEventOutboxStore.Claim claim) {
        final PlatformEventOutboxStore.Event event;
        try {
            event = store.event(claim).orElse(null);
        } catch (RuntimeException databaseFailure) {
            log.warn(
                    "Platform event lookup deferred for {} ({})",
                    claim.id(),
                    databaseFailure.getClass().getSimpleName());
            return;
        }
        if (event == null) return;
        var consumer = registry.acquire(event.sourceType(), event.eventType());
        if (consumer.isEmpty()) {
            try {
                if (!store.releaseWithoutConsumer(claim)) {
                    log.debug("Platform event without a consumer was fenced for {}", claim.id());
                }
            } catch (RuntimeException databaseFailure) {
                log.warn(
                        "Platform event without a consumer could not be released for {} ({})",
                        claim.id(),
                        databaseFailure.getClass().getSimpleName());
            }
            return;
        }
        try (var lease = consumer.get()) {
            if (transaction == null) {
                consumeAndAcknowledge(lease.consumer(), event, claim);
            } else {
                transaction.executeWithoutResult(
                        status -> consumeAndAcknowledge(lease.consumer(), event, claim));
            }
        } catch (AckFencedException fenced) {
            // 到达此处前，事务回滚已撤销消费者产生的全部数据库副作用。
            // The transaction rollback removes every consumer DB side effect before this point.
            log.debug("Platform event ack fenced for {}", claim.id());
        } catch (Exception failure) {
            // 事务及消费者数据库写入已回滚，需单独记录重试状态；fail() 会校验本次领取仍持有有效租约。
            // The transaction (including consumer DB writes) has rolled back. Record retry state
            // separately; fail() checks that this claim still owns a live lease.
            try {
                store.fail(claim, "CONSUMER_FAILED");
            } catch (RuntimeException databaseFailure) {
                log.warn(
                        "Platform event retry state could not be recorded for {} ({})",
                        claim.id(),
                        databaseFailure.getClass().getSimpleName());
            }
            log.warn(
                    "Platform event delivery deferred for {} ({})",
                    claim.id(),
                    cause(failure).getClass().getSimpleName());
        }
    }

    private void consumeAndAcknowledge(
            DurableEventConsumerPort consumer,
            PlatformEventOutboxStore.Event event,
            PlatformEventOutboxStore.Claim claim) {
        final DurableEventConsumerPort.ConsumptionResult result;
        try {
            result =
                    consumer.consumeWithResult(
                            new DurableEventConsumerPort.Event(
                                    event.id(),
                                    event.sourceType(),
                                    event.eventType(),
                                    event.eventKey(),
                                    event.payload(),
                                    event.attempts()));
        } catch (Exception failure) {
            throw new ConsumerFailureException(failure);
        }
        if (!store.deliver(claim, result)) throw new AckFencedException();
    }

    private static Throwable cause(Throwable failure) {
        return failure instanceof ConsumerFailureException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    private static final class ConsumerFailureException extends RuntimeException {
        private ConsumerFailureException(Exception cause) {
            super(cause);
        }
    }

    private static final class AckFencedException extends RuntimeException {}

    @Override
    @PreDestroy
    public void close() {
        if (stopped.compareAndSet(false, true)) workers.shutdownNow();
    }
}
