package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestAdmissionFilterTest {
    @SuppressWarnings("unchecked")
    private RequestAdmissionFilter filter(SimpleMeterRegistry registry) {
        var provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new RequestAdmissionFilter(1, (ObjectProvider<MeterRegistry>) provider);
    }

    @Test
    void rejectsBeforeWorkAndReleasesAfterExceptionWithoutBlockingProbes() throws Exception {
        var registry = new SimpleMeterRegistry();
        var filter = filter(registry);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var active =
                    executor.submit(
                            () -> {
                                assertThrows(
                                        ServletException.class,
                                        () ->
                                                filter.doFilter(
                                                        new MockHttpServletRequest(
                                                                "POST", "/api/write"),
                                                        new MockHttpServletResponse(),
                                                        (request, response) -> {
                                                            entered.countDown();
                                                            try {
                                                                if (!release.await(
                                                                        5, TimeUnit.SECONDS))
                                                                    throw new AssertionError(
                                                                            "release timeout");
                                                            } catch (InterruptedException e) {
                                                                Thread.currentThread().interrupt();
                                                                throw new ServletException(e);
                                                            }
                                                            throw new ServletException(
                                                                    "fixture failure");
                                                        }));
                            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                var rejected = new MockHttpServletResponse();
                filter.doFilter(
                        new MockHttpServletRequest("GET", "/api/read"),
                        rejected,
                        (req, res) -> fail("Rejected work was dispatched"));
                assertEquals(503, rejected.getStatus());
                assertEquals("1", rejected.getHeader("Retry-After"));
                var probe = new MockHttpServletResponse();
                filter.doFilter(
                        new MockHttpServletRequest("GET", "/actuator/health/liveness"),
                        probe,
                        (req, res) -> ((MockHttpServletResponse) res).setStatus(204));
                assertEquals(204, probe.getStatus());
            } finally {
                release.countDown();
            }
            active.get(5, TimeUnit.SECONDS);
        }
        assertEquals(0, registry.get("lumira.http.admission.active").gauge().value());
        assertEquals(1, registry.get("lumira.http.admission.rejected").counter().count());
    }

    @Test
    void retainsAsyncLeaseUntilCompletionAndDoesNotDoubleAcquireOnDispatch() throws Exception {
        var registry = new SimpleMeterRegistry();
        var filter = filter(registry);
        var request = new MockHttpServletRequest("GET", "/api/stream");
        var response = new MockHttpServletResponse();
        request.setAsyncSupported(true);
        var context = new AtomicReference<AsyncContext>();
        filter.doFilter(request, response, (req, res) -> context.set(req.startAsync(req, res)));
        assertEquals(1, registry.get("lumira.http.admission.active").gauge().value());
        filter.doFilter(request, response, (req, res) -> {});
        var blocked = new MockHttpServletResponse();
        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/read"),
                blocked,
                (req, res) -> fail("Async work released its slot early"));
        assertEquals(503, blocked.getStatus());
        context.get().complete();
        assertEquals(0, registry.get("lumira.http.admission.active").gauge().value());
        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/read"),
                new MockHttpServletResponse(),
                (req, res) -> {});
        assertEquals(0, registry.get("lumira.http.admission.active").gauge().value());
    }
}
