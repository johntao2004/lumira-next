package com.lumira.platform.common;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Rejects excess work before authentication, database access, or reading application bodies. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public final class RequestAdmissionFilter implements Filter {
    private static final String LEASE = RequestAdmissionFilter.class.getName() + ".lease";
    private final Semaphore slots;
    private final io.micrometer.core.instrument.Counter rejected;

    public RequestAdmissionFilter(
            @Value("${lumira.http.max-in-flight:256}") int limit,
            ObjectProvider<MeterRegistry> metrics) {
        if (limit < 1 || limit > 4096)
            throw new IllegalArgumentException("Invalid HTTP admission limit");
        slots = new Semaphore(limit);
        var registry = metrics.getIfAvailable();
        rejected = registry == null ? null : registry.counter("lumira.http.admission.rejected");
        if (registry != null)
            registry.gauge(
                    "lumira.http.admission.active",
                    slots,
                    value -> limit - value.availablePermits());
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http)
                || !(response instanceof HttpServletResponse output)
                || !http.getRequestURI().startsWith(http.getContextPath() + "/api/")
                || request.getAttribute(LEASE) != null) {
            chain.doFilter(request, response);
            return;
        }
        if (!slots.tryAcquire()) {
            if (rejected != null) rejected.increment();
            output.setStatus(503);
            output.setHeader("Retry-After", "1");
            output.setHeader("Cache-Control", "no-store");
            output.setContentType("application/json;charset=UTF-8");
            output.getWriter().write("{\"code\":\"serverBusy\",\"detail\":\"serverBusy\"}");
            return;
        }
        var lease = new Lease();
        request.setAttribute(LEASE, lease);
        try {
            chain.doFilter(request, response);
        } finally {
            if (request.isAsyncStarted()) {
                try {
                    request.getAsyncContext().addListener(lease);
                } catch (IllegalStateException alreadyCompleted) {
                    lease.close();
                }
            } else lease.close();
        }
    }

    private final class Lease implements AsyncListener, AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) slots.release();
        }

        @Override
        public void onComplete(AsyncEvent event) {
            close();
        }

        // An error or timeout may be dispatched for handling; retain the slot until completion.
        @Override
        public void onTimeout(AsyncEvent event) {}

        @Override
        public void onError(AsyncEvent event) {}

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }
}
