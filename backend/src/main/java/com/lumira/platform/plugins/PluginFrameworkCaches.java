package com.lumira.platform.plugins;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.jackson.AbstractModelConverter;
import java.lang.reflect.Field;
import java.util.Map;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.web.method.annotation.AbstractNamedValueMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodArgumentResolverComposite;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.handler.HandlerExceptionResolverComposite;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor;
import tools.jackson.databind.ObjectMapper;

/**
 * The shared Spring MVC adapter outlives each plugin. Unregistering mappings does not evict its
 * Class/MethodParameter caches. Keep this version-specific integration in one place, fail fast on
 * incompatible framework changes, and evict only the drained classloader's entries.
 */
final class PluginFrameworkCaches {
    private static final Field[] ADAPTER_MAPS = {
        field(RequestMappingHandlerAdapter.class, "sessionAttributesHandlerCache"),
        field(RequestMappingHandlerAdapter.class, "initBinderCache"),
        field(RequestMappingHandlerAdapter.class, "modelAttributeCache")
    };
    private static final Field ARGUMENTS =
            field(RequestMappingHandlerAdapter.class, "argumentResolvers");
    private static final Field BINDERS =
            field(RequestMappingHandlerAdapter.class, "initBinderArgumentResolvers");
    private static final Field ARGUMENT_CACHE =
            field(HandlerMethodArgumentResolverComposite.class, "argumentResolverCache");
    private static final Field NAMED_CACHE =
            field(AbstractNamedValueMethodArgumentResolver.class, "namedValueInfoCache");
    private static final Field EXCEPTIONS =
            field(ExceptionHandlerExceptionResolver.class, "exceptionHandlerCache");
    private static final Field RESPONSE_CONTROLLERS =
            field(RequestResponseBodyMethodProcessor.class, "responseBodyControllerCache");
    private static final Field MODEL_TYPES =
            field(AbstractModelConverter.class, "_resolvedTypeNames");
    private static final Field MODEL_MAPPER = field(AbstractModelConverter.class, "_mapper");
    private static final Field CONVERSIONS =
            field(GenericConversionService.class, "converterCache");

    private PluginFrameworkCaches() {}

    static void validate() {
        // Static initialization verifies the pinned framework integration before loading a plugin.
    }

    static void release(ApplicationContext host, ClassLoader loader) {
        if (loader == null) return;
        host.getBeansOfType(RequestMappingHandlerAdapter.class)
                .values()
                .forEach(adapter -> release(adapter, loader));
        host.getBeansOfType(HandlerExceptionResolver.class)
                .values()
                .forEach(resolver -> release(resolver, loader));
        // Conversion keys retain the declaring method even for primitive target types. Soft
        // references otherwise keep an unloaded plugin alive until memory pressure occurs. Clear
        // only lookup results; registered converters remain available to concurrent requests.
        host.getBeansOfType(GenericConversionService.class)
                .values()
                .forEach(service -> ((Map<?, ?>) read(CONVERSIONS, service)).clear());
        // Jackson exposes a supported, thread-safe cache reset, but no per-classloader eviction.
        host.getBeansOfType(ObjectMapper.class).values().forEach(ObjectMapper::clearCaches);
        host.getBeansOfType(com.fasterxml.jackson.databind.ObjectMapper.class)
                .values()
                .forEach(com.fasterxml.jackson.databind.ObjectMapper::clearCaches);
        releaseDocumentationCaches();
        CachedIntrospectionResults.clearClassLoader(loader);
    }

    static void release(RequestMappingHandlerAdapter adapter, ClassLoader loader) {
        for (var cache : ADAPTER_MAPS) evict(cache, adapter, loader);
        release((HandlerMethodArgumentResolverComposite) read(ARGUMENTS, adapter), loader);
        release((HandlerMethodArgumentResolverComposite) read(BINDERS, adapter), loader);
        var returns = adapter.getReturnValueHandlers();
        if (returns != null) returns.forEach(handler -> releaseResponseProcessor(handler, loader));
        adapter.getMessageConverters().forEach(PluginFrameworkCaches::clearConverter);
    }

    static void release(HandlerExceptionResolver resolver, ClassLoader loader) {
        if (resolver instanceof HandlerExceptionResolverComposite composite) {
            var resolvers = composite.getExceptionResolvers();
            if (resolvers != null) resolvers.forEach(child -> release(child, loader));
        } else if (resolver instanceof ExceptionHandlerExceptionResolver exceptions) {
            evict(EXCEPTIONS, exceptions, loader);
            release(exceptions.getArgumentResolvers(), loader);
            var returns = exceptions.getReturnValueHandlers();
            if (returns != null)
                returns.getHandlers().forEach(handler -> releaseResponseProcessor(handler, loader));
            exceptions.getMessageConverters().forEach(PluginFrameworkCaches::clearConverter);
        }
    }

    private static void clearConverter(HttpMessageConverter<?> converter) {
        if (converter instanceof AbstractJacksonHttpMessageConverter<?> jackson)
            jackson.getMapper().clearCaches();
        else if (converter instanceof AbstractJackson2HttpMessageConverter jackson)
            jackson.getObjectMapper().clearCaches();
    }

    /** Also run when documentation completes: a concurrent scan can outlive route withdrawal. */
    static void releaseDocumentationCaches() {
        for (boolean openApi31 : new boolean[] {false, true}) {
            for (var converter : ModelConverters.getInstance(openApi31).getConverters()) {
                if (converter instanceof AbstractModelConverter) {
                    ((Map<?, ?>) read(MODEL_TYPES, converter)).clear();
                    ((com.fasterxml.jackson.databind.ObjectMapper) read(MODEL_MAPPER, converter))
                            .clearCaches();
                }
            }
        }
    }

    private static void release(
            HandlerMethodArgumentResolverComposite composite, ClassLoader loader) {
        if (composite == null) return;
        evict(ARGUMENT_CACHE, composite, loader);
        for (var resolver : composite.getResolvers()) {
            if (resolver instanceof AbstractNamedValueMethodArgumentResolver)
                evict(NAMED_CACHE, resolver, loader);
            releaseResponseProcessor(resolver, loader);
        }
    }

    private static void releaseResponseProcessor(Object handler, ClassLoader loader) {
        if (handler instanceof RequestResponseBodyMethodProcessor)
            evict(RESPONSE_CONTROLLERS, handler, loader);
    }

    private static void evict(Field field, Object owner, ClassLoader loader) {
        var cache = (Map<?, ?>) read(field, owner);
        cache.keySet()
                .removeIf(
                        key -> {
                            Class<?> type =
                                    key instanceof Class<?> c
                                            ? c
                                            : key instanceof MethodParameter p
                                                    ? p.getContainingClass()
                                                    : null;
                            return type != null && type.getClassLoader() == loader;
                        });
    }

    private static Object read(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(
                    "Plugin framework cache integration unavailable", error);
        }
    }

    private static Field field(Class<?> type, String name) {
        try {
            var field = type.getDeclaredField(name);
            if (!field.trySetAccessible())
                throw new IllegalStateException("Plugin framework cache inaccessible: " + name);
            return field;
        } catch (NoSuchFieldException error) {
            throw new IllegalStateException(
                    "Incompatible Spring MVC plugin cache contract: " + name, error);
        }
    }
}
