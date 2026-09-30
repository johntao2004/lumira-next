package com.lumira.platform.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Metrics describe internals (pool sizes, endpoints, JVM), so they are never anonymous: a scraper
 * must present the configured bearer token. Without a configured token the endpoint does not exist.
 */
final class MetricsTokenFilter extends OncePerRequestFilter {
    static final int MINIMUM_TOKEN_LENGTH = 32;
    private final byte[] expected;

    MetricsTokenFilter(String token) {
        expected =
                token != null && token.length() >= MINIMUM_TOKEN_LENGTH
                        ? token.getBytes(StandardCharsets.UTF_8)
                        : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/actuator/prometheus");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expected == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String header = request.getHeader("Authorization");
        boolean allowed =
                header != null
                        && header.startsWith("Bearer ")
                        && MessageDigest.isEqual(
                                header.substring(7).getBytes(StandardCharsets.UTF_8), expected);
        if (!allowed) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            return;
        }
        chain.doFilter(request, response);
    }
}
