package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTest {
    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    private String run(String supplied, AtomicReference<String> seenInChain) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/x");
        if (supplied != null) request.addHeader(RequestCorrelationFilter.HEADER, supplied);
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request,
                response,
                new MockFilterChain() {
                    @Override
                    public void doFilter(
                            jakarta.servlet.ServletRequest req,
                            jakarta.servlet.ServletResponse res) {
                        seenInChain.set(MDC.get(RequestCorrelationFilter.MDC_KEY));
                    }
                });
        return response.getHeader(RequestCorrelationFilter.HEADER);
    }

    @Test
    void acceptsSafeCallerIdAndExposesItToLogsAndResponse() throws Exception {
        var seen = new AtomicReference<String>();
        assertEquals("trace-1234.abcd", run("trace-1234.abcd", seen));
        assertEquals("trace-1234.abcd", seen.get());
    }

    @Test
    void replacesUnsafeOrOversizedIdsSoLogsCannotBeForged() throws Exception {
        for (String hostile :
                new String[] {"x\nINFO forged", "short", "a".repeat(65), "id with space!"}) {
            var seen = new AtomicReference<String>();
            String issued = run(hostile, seen);
            assertNotEquals(hostile, issued);
            assertDoesNotThrow(() -> java.util.UUID.fromString(issued));
            assertEquals(issued, seen.get());
        }
    }

    @Test
    void generatesIdWhenAbsentAndClearsMdcAfterwards() throws Exception {
        var seen = new AtomicReference<String>();
        assertNotNull(run(null, seen));
        assertNotNull(seen.get());
        assertNull(MDC.get(RequestCorrelationFilter.MDC_KEY));
    }

    @Test
    void writesOneAccessLineWithoutTheQueryString() throws Exception {
        var logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("lumira.access");
        var appender =
                new ch.qos.logback.core.read.ListAppender<
                        ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var request = new MockHttpServletRequest("GET", "/api/platform/users");
            request.setQueryString("token=secret-value");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertEquals(1, appender.list.size());
            String line = appender.list.get(0).getFormattedMessage();
            assertTrue(line.startsWith("GET /api/platform/users -> 200 in "), line);
            assertFalse(line.contains("secret-value"), line);
            assertEquals(
                    response.getHeader(RequestCorrelationFilter.HEADER),
                    appender.list.get(0).getMDCPropertyMap().get(RequestCorrelationFilter.MDC_KEY));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void healthProbesDoNotFloodTheInfoLog() throws Exception {
        var logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("lumira.access");
        var appender =
                new ch.qos.logback.core.read.ListAppender<
                        ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            filter.doFilter(
                    new MockHttpServletRequest("GET", "/actuator/health/readiness"),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
            assertTrue(
                    appender.list.stream()
                            .noneMatch(
                                    e ->
                                            e.getLevel()
                                                    .isGreaterOrEqual(
                                                            ch.qos.logback.classic.Level.INFO)));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
