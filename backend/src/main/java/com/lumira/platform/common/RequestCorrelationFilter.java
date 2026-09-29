package com.lumira.platform.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id that appears in each log line (MDC key {@code requestId})
 * and in the {@code X-Request-Id} response header, and writes one access-log line per request. A
 * caller-supplied id is accepted only when it is short and made of safe characters, so it cannot be
 * used for log forging. The query string is never logged because it may carry tokens.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    private static final Logger access = LoggerFactory.getLogger("lumira.access");

    static String resolve(String supplied) {
        return supplied != null && SAFE.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            logAccess(request, response, (System.nanoTime() - started) / 1_000_000);
            MDC.remove(MDC_KEY);
        }
    }

    private static void logAccess(HttpServletRequest request, HttpServletResponse response, long ms) {
        String path = request.getRequestURI();
        boolean probe = path.startsWith("/actuator/");
        if (probe ? !access.isDebugEnabled() : !access.isInfoEnabled()) return;
        String line = request.getMethod() + " " + path + " -> " + response.getStatus() + " in " + ms + " ms";
        if (probe) access.debug(line);
        else access.info(line);
    }
}
