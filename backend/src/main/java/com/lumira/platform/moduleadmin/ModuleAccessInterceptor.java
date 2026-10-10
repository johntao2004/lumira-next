package com.lumira.platform.moduleadmin;

import com.lumira.platform.modules.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/** Runs after security filters, on the resolved handler rather than a spoofable URL prefix. */
public final class ModuleAccessInterceptor implements HandlerInterceptor {
    private final ModuleRegistry registry;
    private final ModuleRuntime runtime;

    public ModuleAccessInterceptor(ModuleRegistry registry, ModuleRuntime runtime) {
        this.registry = registry;
        this.runtime = runtime;
    }

    static ModuleEndpoint owner(HandlerMethod handler) {
        var method =
                AnnotatedElementUtils.findMergedAnnotation(
                        handler.getMethod(), ModuleEndpoint.class);
        return method != null
                ? method
                : AnnotatedElementUtils.findMergedAnnotation(
                        handler.getBeanType(), ModuleEndpoint.class);
    }

    static void validate(HandlerMethod handler, ModuleRegistry registry) {
        var owner = owner(handler);
        if (owner == null)
            throw new IllegalStateException(
                    "Endpoint has no module owner: " + handler.getBeanType().getName());
        if (registry.modules().stream().noneMatch(m -> m.id().equals(owner.value())))
            throw new IllegalStateException("Unregistered endpoint module: " + owner.value());
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) return true;
        var owner = owner(method);
        if (owner == null) return true;
        var module =
                registry.modules().stream()
                        .filter(m -> m.id().equals(owner.value()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "moduleUnavailable"));
        if (!module.core() && !runtime.isEnabled(module.id()))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "moduleUnavailable");
        return true;
    }
}
