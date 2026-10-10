package com.lumira.platform.plugins;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Rejects application traffic before authentication if the approved plugin snapshot is unsafe. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class PluginRuntimeReadinessFilter implements Filter, Ordered {
    private final PluginRuntime runtime;

    public PluginRuntimeReadinessFilter(PluginRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)
                || !httpRequest
                        .getRequestURI()
                        .startsWith(httpRequest.getContextPath() + "/api/")) {
            chain.doFilter(request, response);
            return;
        }
        if ("GET".equals(httpRequest.getMethod())
                && httpRequest
                        .getServletPath()
                        .startsWith("/api/platform/modules/plugins/operations/")) {
            chain.doFilter(request, response);
            return;
        }
        if (runtime.isReadyForTraffic()) {
            chain.doFilter(request, response);
            return;
        }
        httpResponse.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        httpResponse.setContentType("application/json;charset=UTF-8");
        httpResponse.setHeader("Cache-Control", "no-store");
        httpResponse.getWriter().write("{\"code\":\"pluginRuntimeNotReady\"}");
    }
}
