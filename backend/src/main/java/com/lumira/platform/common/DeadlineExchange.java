package com.lumira.platform.common;

import java.io.Closeable;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Total transport deadline with active cancellation and bounded, no-queue in-flight work. */
public final class DeadlineExchange {
    private static final ThreadPoolExecutor WORKERS =
            new ThreadPoolExecutor(
                    0,
                    32,
                    30,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    Thread.ofVirtual().name("lumira-outbound-", 0).factory(),
                    new ThreadPoolExecutor.AbortPolicy());

    private DeadlineExchange() {}

    @FunctionalInterface
    public interface Operation<T> {
        T run(Scope scope) throws Exception;
    }

    public static final class Busy extends IOException {
        public Busy() {
            super("Outbound transport is busy");
        }
    }

    public static <T> T run(Duration timeout, Operation<T> operation)
            throws IOException, InterruptedException {
        try (var scope = new Scope(timeout)) {
            Future<T> work;
            try {
                work = WORKERS.submit(() -> operation.run(scope));
            } catch (RejectedExecutionException busy) {
                throw new Busy();
            }
            try {
                T result = work.get(scope.remaining(), TimeUnit.NANOSECONDS);
                scope.check();
                return result;
            } catch (TimeoutException expired) {
                throw new HttpTimeoutException("request timed out");
            } catch (ExecutionException failure) {
                scope.check();
                if (failure.getCause() instanceof IOException io) throw io;
                if (failure.getCause() instanceof InterruptedException interrupted)
                    throw interrupted;
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failure.getCause() instanceof Error error) throw error;
                throw new IOException("Outbound request failed", failure.getCause());
            } finally {
                // Closing tracked sockets and body streams unblocks reads; interrupt alone is not
                // sufficient for every URL handler. Late registration closes the resource too.
                scope.close();
                work.cancel(true);
            }
        }
    }

    public static final class Scope implements AutoCloseable {
        private final long started = System.nanoTime();
        private final long limit;
        private final List<Closeable> resources = new ArrayList<>();
        private boolean closed;

        private Scope(Duration timeout) {
            limit = timeout.toNanos();
            if (limit <= 0)
                throw new IllegalArgumentException("Positive request deadline required");
        }

        private long remaining() throws HttpTimeoutException {
            long left = limit - (System.nanoTime() - started);
            if (left <= 0) throw new HttpTimeoutException("request timed out");
            return left;
        }

        public void check() throws HttpTimeoutException {
            remaining();
            synchronized (this) {
                if (closed) throw new HttpTimeoutException("request timed out");
            }
        }

        public <T extends Closeable> T track(T resource) throws IOException {
            synchronized (this) {
                if (!closed) {
                    resources.add(resource);
                    return resource;
                }
            }
            closeQuietly(resource);
            throw new HttpTimeoutException("request timed out");
        }

        @Override
        public void close() {
            List<Closeable> closing;
            synchronized (this) {
                if (closed) return;
                closed = true;
                closing = List.copyOf(resources);
                resources.clear();
            }
            closing.forEach(Scope::closeQuietly);
        }

        private static void closeQuietly(Closeable resource) {
            try {
                resource.close();
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }
}
