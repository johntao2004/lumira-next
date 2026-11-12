package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

public class PluginFrameworkCachesTest {
    @Test
    void releasesUsedPluginControllerCachesWithoutEvictingHostControllers() throws Exception {
        var parent = getClass().getClassLoader();
        var loader =
                new ClassLoader(parent) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        if (!name.equals(
                                com.lumira.testfixtures.CachePluginController.class.getName()))
                            return super.loadClass(name, resolve);
                        var existing = findLoadedClass(name);
                        if (existing != null) return existing;
                        try (var input =
                                parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
                            byte[] bytes = input.readAllBytes();
                            return defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException error) {
                            throw new ClassNotFoundException(name, error);
                        }
                    }
                };
        var plugin =
                loader.loadClass(com.lumira.testfixtures.CachePluginController.class.getName())
                        .getConstructor()
                        .newInstance();
        var legacy =
                new org.springframework.http.converter.yaml
                        .MappingJackson2YamlHttpMessageConverter();
        var mvc =
                MockMvcBuilders.standaloneSetup(plugin, new HostController())
                        .setMessageConverters(
                                new org.springframework.http.converter.json
                                        .JacksonJsonHttpMessageConverter(),
                                legacy)
                        .build();
        mvc.perform(get("/plugin/cache").param("value", "plugin")).andExpect(status().isOk());
        mvc.perform(get("/host/cache").param("value", "host")).andExpect(status().isOk());
        mvc.perform(get("/plugin/cache").param("value", "fail")).andExpect(status().isBadRequest());
        mvc.perform(get("/plugin/typed").accept("application/yaml")).andExpect(status().isOk());
        var serializers =
                (com.fasterxml.jackson.databind.ser.DefaultSerializerProvider)
                        legacy.getObjectMapper().getSerializerProvider();
        assertTrue(serializers.cachedSerializersCount() > 0);
        var converters = io.swagger.v3.core.converter.ModelConverters.getInstance(true);
        converters.readAll(plugin.getClass());
        var resolver =
                converters.getConverters().stream()
                        .filter(io.swagger.v3.core.jackson.ModelResolver.class::isInstance)
                        .findFirst()
                        .orElseThrow();
        var models = (Map<?, ?>) ReflectionTestUtils.getField(resolver, "_resolvedTypeNames");
        assertFalse(models.isEmpty());
        var host = mvc.getDispatcherServlet().getWebApplicationContext();
        var conversionService =
                host.getBean(
                        org.springframework.core.convert.support.GenericConversionService.class);
        var conversions =
                (Map<?, ?>) ReflectionTestUtils.getField(conversionService, "converterCache");
        assertFalse(conversions.isEmpty());
        var adapter = host.getBean(RequestMappingHandlerAdapter.class);
        var processor =
                adapter.getReturnValueHandlers().stream()
                        .filter(
                                org.springframework.web.servlet.mvc.method.annotation
                                                .RequestResponseBodyMethodProcessor.class
                                        ::isInstance)
                        .findFirst()
                        .orElseThrow();
        var responseTypes =
                (Map<?, ?>) ReflectionTestUtils.getField(processor, "responseBodyControllerCache");
        assertTrue(responseTypes.containsKey(plugin.getClass()));
        var cache =
                (Map<?, ?>) ReflectionTestUtils.getField(adapter, "sessionAttributesHandlerCache");
        assertTrue(cache.containsKey(plugin.getClass()));
        assertTrue(cache.containsKey(HostController.class));
        PluginFrameworkCaches.release(host, loader);
        assertTrue(conversions.isEmpty());
        assertFalse(cache.containsKey(plugin.getClass()));
        assertFalse(responseTypes.containsKey(plugin.getClass()));
        assertTrue(cache.containsKey(HostController.class));
        assertEquals(0, serializers.cachedSerializersCount());
        assertTrue(models.isEmpty());
        mvc.perform(get("/host/cache").param("value", "after")).andExpect(status().isOk());
        // A new registration may use the same classloader again; eviction is not permanent
        // disablement.
        mvc.perform(get("/plugin/cache").param("value", "again")).andExpect(status().isOk());
        assertTrue(cache.containsKey(plugin.getClass()));
    }

    @RestController
    public static class HostController {
        @GetMapping("/host/cache")
        public Map<String, String> value(@RequestParam String value) {
            return Map.of("value", value);
        }
    }
}
