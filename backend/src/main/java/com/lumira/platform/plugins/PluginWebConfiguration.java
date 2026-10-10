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
                        return true;
                    }

                    @Override
                    public void afterCompletion(
                            HttpServletRequest req,
                            HttpServletResponse res,
                            Object handler,
                            Exception e)
                            throws Exception {
                        var lease = req.getAttribute(LEASE);
                        if (lease instanceof AutoCloseable close) {
                            req.removeAttribute(LEASE);
                            close.close();
                        }
                    }
                });
    }
}
