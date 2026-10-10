package com.lumira.platform.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PluginRuntimeReadinessFilterTest {
    @Test
    void unreadyRuntimeRejectsPasswordLoginBeforeAuthenticationCanCreateASession()
            throws Exception {
        PluginRuntime runtime = mock(PluginRuntime.class);
        when(runtime.isReadyForTraffic()).thenReturn(false);
        PluginRuntimeReadinessFilter filter = new PluginRuntimeReadinessFilter(runtime);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reachedSecurityChain = new AtomicBoolean();
        FilterChain chain = (servletRequest, servletResponse) -> reachedSecurityChain.set(true);

        filter.doFilter(request, response, chain);

        assertThat(filter.getOrder()).isLessThan(-100);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("pluginRuntimeNotReady");
        assertThat(request.getSession(false)).isNull();
        assertThat(reachedSecurityChain).isFalse();
    }

    @Test
    void actuatorHealthRemainsReachableWhileApplicationRequestsAreGated() throws Exception {
        PluginRuntime runtime = mock(PluginRuntime.class);
        when(runtime.isReadyForTraffic()).thenReturn(false);
        PluginRuntimeReadinessFilter filter = new PluginRuntimeReadinessFilter(runtime);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reachedHealth = new AtomicBoolean();
        FilterChain chain = (servletRequest, servletResponse) -> reachedHealth.set(true);

        filter.doFilter(request, response, chain);

        assertThat(reachedHealth).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
