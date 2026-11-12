package com.lumira.platform.plugins;

import com.lumira.platform.modules.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class PluginWebConfiguration implements WebMvcConfigurer {
    private final ObjectProvider<PluginRuntime> plugins;
    private final ModuleRegistry registry;

    public PluginWebConfiguration(ObjectProvider<PluginRuntime> plugins, ModuleRegistry registry) {
        this.plugins = plugins;
        this.registry = registry;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                new AsyncHandlerInterceptor() {
                    private static final String LEASE =
                            PluginWebConfiguration.class.getName() + ".lease";
                    private static final String SCOPE = LEASE + ".scope";

                    @Override
                    public boolean preHandle(
                            HttpServletRequest req, HttpServletResponse res, Object handler) {
                        if (!(handler instanceof HandlerMethod method)) return true;
                        var owner =
                                AnnotatedElementUtils.findMergedAnnotation(
                                        method.getBeanType(), ModuleEndpoint.class);
                        if (owner == null
                                || !PluginWebConfiguration.this.registry.isPlugin(owner.value()))
                            return true;
                        var runtime = plugins.getObject();
                        if (!runtime.endpointAuthorized(
                                owner.value(),
                                req.getMethod(),
                                req.getRequestURI(),
                                SecurityContextHolder.getContext().getAuthentication()))
                            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
                        if (req.getAttribute(LEASE) == null)
                            req.setAttribute(LEASE, runtime.acquire(owner.value()));
                        var snapshot = runtime.invocationOwner(owner.value());
                        req.setAttribute(SCOPE, PluginInvocationContext.open(snapshot));
                        org.springframework.web.context.request.async.WebAsyncUtils.getAsyncManager(
                                        req)
                                .registerCallableInterceptor(
                                        SCOPE,
                                        new org.springframework.web.context.request.async
                                                .CallableProcessingInterceptor() {
                                            private final ThreadLocal<PluginInvocationContext>
                                                    worker = new ThreadLocal<>();

                                            @Override
                                            public <T> void preProcess(
                                                    org.springframework.web.context.request
                                                                    .NativeWebRequest
                                                            request,
                                                    java.util.concurrent.Callable<T> task) {
                                                worker.set(PluginInvocationContext.open(snapshot));
                                            }

                                            @Override
                                            public <T> void postProcess(
                                                    org.springframework.web.context.request
                                                                    .NativeWebRequest
                                                            request,
                                                    java.util.concurrent.Callable<T> task,
                                                    Object result) {
                                                var scope = worker.get();
                                                try {
                                                    if (scope != null) scope.close();
                                                } finally {
                                                    worker.remove();
                                                }
                                            }
                                        });
                        return true;
                    }

                    private void closeScope(HttpServletRequest request) {
                        var value = request.getAttribute(SCOPE);
                        request.removeAttribute(SCOPE);
                        if (value instanceof PluginInvocationContext scope) scope.close();
                    }

                    @Override
                    public void afterConcurrentHandlingStarted(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            Object handler) {
                        closeScope(request);
                    }

                    @Override
                    public void afterCompletion(
                            HttpServletRequest req,
                            HttpServletResponse res,
                            Object handler,
                            Exception e)
                            throws Exception {
                        closeScope(req);
                        var lease = req.getAttribute(LEASE);
                        if (lease instanceof AutoCloseable close) {
                            req.removeAttribute(LEASE);
                            close.close();
                        }
                        String path = req.getRequestURI();
                        if (path.equals("/api/platform/api-docs")
                                || path.equals("/api/platform/api-docs.yaml")
                                || path.startsWith("/api/platform/api-docs/"))
                            PluginFrameworkCaches.releaseDocumentationCaches();
                    }
                });
    }
}
