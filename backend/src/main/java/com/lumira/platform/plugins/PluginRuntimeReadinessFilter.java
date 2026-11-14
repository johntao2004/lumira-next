/**
 * 在认证前拒绝插件运行图尚未安全就绪时的业务 API 流量，同时保留生命周期操作状态查询入口。 Rejects business API traffic before authentication
 * while the plugin graph is unsafe or unready, preserving access to lifecycle-operation status
 * queries.
 */
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

/**
 * 批准的插件快照不安全时，在认证前拒绝应用流量。 Rejects application traffic before authentication if the approved plugin
 * snapshot is unsafe.
 */
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
                || !isApiRequest(httpRequest)) {
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
        try (var scope = runtime.openTrafficScope()) {
            if (runtime.isReadyForTraffic()) {
                chain.doFilter(request, response);
                return;
            }
        }
        httpResponse.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        httpResponse.setContentType("application/json;charset=UTF-8");
        httpResponse.setHeader("Cache-Control", "no-store");
        httpResponse.getWriter().write("{\"code\":\"pluginRuntimeNotReady\"}");
    }

    private static boolean isApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.isEmpty()) {
            return uri.equals("/api") || uri.startsWith("/api/");
        }
        int cpLen = contextPath.length();
        if (uri.startsWith(contextPath)) {
            int remaining = uri.length() - cpLen;
            if (remaining == 4 && uri.endsWith("/api")) return true;
            return uri.startsWith("/api/", cpLen);
        }
        return false;
    }
}
