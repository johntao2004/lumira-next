package com.lumira.platform.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Binary uploads still expose human text in the filename query parameter. */
@Component
public class ContentPolicyParameterInterceptor implements HandlerInterceptor {
    private final ContentPolicyGuard guard;

    public ContentPolicyParameterInterceptor(
            org.springframework.beans.factory.ObjectProvider<ContentPolicyPort> policies) {
        this.guard = new ContentPolicyGuard(policies);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!java.util.Set.of("POST", "PUT", "PATCH").contains(request.getMethod())
                || !(handler instanceof HandlerMethod method)) return true;
        if (method.getBeanType().isAnnotationPresent(ContentPolicyExempt.class)
                || method.hasMethodAnnotation(ContentPolicyExempt.class)) return true;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null
                && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            guard.requireAllowed(
                    auth.getName(),
                    request.getParameter("filename"),
                    method.getBeanType().getSimpleName()
                            + "."
                            + method.getMethod().getName()
                            + ":filename");
        }
        return true;
    }
}
