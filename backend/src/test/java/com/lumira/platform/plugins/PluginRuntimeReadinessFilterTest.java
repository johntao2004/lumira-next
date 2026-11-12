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
    void requestScopeClosesWhenDownstreamThrows() throws Exception {
        var runtime = mock(PluginRuntime.class);
        var scope = mock(PluginRuntime.TrafficScope.class);
        when(runtime.openTrafficScope()).thenReturn(scope);
        when(runtime.isReadyForTraffic()).thenReturn(true);
        var filter = new PluginRuntimeReadinessFilter(runtime);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        new MockHttpServletRequest("GET", "/api/test"),
                                        new MockHttpServletResponse(),
                                        (request, response) -> {
                                            throw new jakarta.servlet.ServletException("fixture");
                                        }))
                .isInstanceOf(jakarta.servlet.ServletException.class);
        org.mockito.Mockito.verify(scope).close();
    }

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

    @Test
    void nonApiRequestsAndCustomContextPathFilterCorrectly() throws Exception {
        PluginRuntime runtime = mock(PluginRuntime.class);
        when(runtime.isReadyForTraffic()).thenReturn(false);
        PluginRuntimeReadinessFilter filter = new PluginRuntimeReadinessFilter(runtime);

        MockHttpServletRequest nonApi = new MockHttpServletRequest("GET", "/static/asset.js");
        MockHttpServletResponse nonApiResponse = new MockHttpServletResponse();
        AtomicBoolean reachedNonApi = new AtomicBoolean();
        filter.doFilter(nonApi, nonApiResponse, (req, res) -> reachedNonApi.set(true));
        assertThat(reachedNonApi).isTrue();

        MockHttpServletRequest customContext =
                new MockHttpServletRequest("GET", "/custom/api/endpoint");
        customContext.setContextPath("/custom");
        MockHttpServletResponse customContextResponse = new MockHttpServletResponse();
        AtomicBoolean reachedCustom = new AtomicBoolean();
        filter.doFilter(
                customContext, customContextResponse, (req, res) -> reachedCustom.set(true));
        assertThat(reachedCustom).isFalse();
        assertThat(customContextResponse.getStatus()).isEqualTo(503);

        MockHttpServletRequest exactApi = new MockHttpServletRequest("GET", "/api");
        MockHttpServletResponse exactApiResponse = new MockHttpServletResponse();
        AtomicBoolean reachedExactApi = new AtomicBoolean();
        filter.doFilter(exactApi, exactApiResponse, (req, res) -> reachedExactApi.set(true));
        assertThat(reachedExactApi).isFalse();
        assertThat(exactApiResponse.getStatus()).isEqualTo(503);
    }
}
