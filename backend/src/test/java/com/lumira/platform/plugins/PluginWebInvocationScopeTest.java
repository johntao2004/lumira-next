package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.lumira.platform.modules.ModuleEndpoint;
import com.lumira.platform.modules.ModuleRegistry;
import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

class PluginWebInvocationScopeTest {
    private MockMvc mvc;
    private final AtomicInteger closed = new AtomicInteger();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var runtime = mock(PluginRuntime.class);
        when(runtime.endpointAuthorized(anyString(), anyString(), anyString(), any()))
                .thenReturn(true);
        when(runtime.invocationOwner("sample"))
                .thenReturn(
                        new PluginInvocationContext.Owner(
                                "sample", "a".repeat(64), 1, UUID.randomUUID()));
        when(runtime.acquire("sample"))
                .thenAnswer(invocation -> (AutoCloseable) closed::incrementAndGet);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("runtime", runtime);
        var modules =
                new ModuleRegistry(
                        List.of(
                                new PlatformModule(
                                        "identity", "Identity", "1.0.0", List.of(), true)));
        modules.registerPlugin(new PlatformModule("sample", "Sample", "1.0.0", List.of()));
        var registry = new InterceptorRegistry();
        new PluginWebConfiguration(beans.getBeanProvider(PluginRuntime.class), modules)
                .addInterceptors(registry);
        List<HandlerInterceptor> interceptors =
                ReflectionTestUtils.invokeMethod(registry, "getInterceptors");
        assertNotNull(interceptors);
        mvc =
                MockMvcBuilders.standaloneSetup(new Controller())
                        .addInterceptors(interceptors.toArray(HandlerInterceptor[]::new))
                        .build();
    }

    @AfterEach
    void noLeakedOwnership() {
        assertTrue(PluginInvocationContext.owners().isEmpty());
    }

    @Test
    void synchronousHandlerHasOwnershipAndReleasesItsLease() throws Exception {
        mvc.perform(get("/scope/sync"))
                .andExpect(status().isOk())
                .andExpect(content().string("sample"));
        assertEquals(1, closed.get());
    }

    @Test
    void callableWorkerReceivesTheCapturedGenerationAndCleansBothThreads() throws Exception {
        var result =
                mvc.perform(get("/scope/async")).andExpect(request().asyncStarted()).andReturn();
        assertTrue(
                PluginInvocationContext.owners().isEmpty(),
                "Servlet thread retained ownership during async work");
        assertEquals("sample", result.getAsyncResult(5000));
        assertEquals(0, closed.get(), "Async response released its drain lease early");
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string("sample"));
        assertEquals(1, closed.get());
    }

    @Test
    void failedHandlerDoesNotLeaveOwnershipOnTheServletThread() {
        assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(get("/scope/fail")));
        assertEquals(1, closed.get());
    }

    @ModuleEndpoint("sample")
    @RestController
    static class Controller {
        @GetMapping("/scope/sync")
        String sync() {
            return current();
        }

        @GetMapping("/scope/async")
        Callable<String> async() {
            return Controller::current;
        }

        @GetMapping("/scope/fail")
        String failure() {
            assertEquals("sample", current());
            throw new IllegalStateException("fixture");
        }

        private static String current() {
            var owners = PluginInvocationContext.owners();
            assertEquals(1, owners.size());
            assertEquals(1, owners.getFirst().generation());
            return owners.getFirst().moduleId();
        }
    }
}
