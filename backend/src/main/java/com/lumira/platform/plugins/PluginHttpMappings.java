package com.lumira.platform.plugins;

import java.lang.reflect.Method;
import java.util.*;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

final class PluginHttpMappings {
    static RequestMappingInfo mapping(
            Class<?> type, Method method, RequestMappingHandlerMapping host) {
        var route = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (route == null) return null;
        var prefix = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        var child = info(route, host);
        return prefix == null ? child : info(prefix, host).combine(child);
    }

    private static RequestMappingInfo info(RequestMapping r, RequestMappingHandlerMapping host) {
        return RequestMappingInfo.paths(r.path().length == 0 ? r.value() : r.path())
                .methods(r.method())
                .params(r.params())
                .headers(r.headers())
                .consumes(r.consumes())
                .produces(r.produces())
                .options(host.getBuilderConfiguration())
                .build();
    }
}
