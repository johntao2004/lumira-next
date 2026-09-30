package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MetricsTokenFilterTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private static MockHttpServletResponse call(
            String configured, String path, String authorization) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        if (authorization != null) request.addHeader("Authorization", authorization);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new MetricsTokenFilter(configured).doFilter(request, response, chain);
        response.setForwardedUrl(chain.getRequest() == null ? null : "passed");
        return response;
    }

    @Test
    void disabledWithoutAConfiguredOrLongEnoughToken() throws Exception {
        assertEquals(404, call("", "/actuator/prometheus", "Bearer x").getStatus());
        assertEquals(404, call("short", "/actuator/prometheus", "Bearer short").getStatus());
    }

    @Test
    void requiresExactBearerToken() throws Exception {
        for (String header :
                new String[] {
                    null, "Bearer wrong", "Basic " + TOKEN, TOKEN, "Bearer " + TOKEN + "x"
                }) {
            var response = call(TOKEN, "/actuator/prometheus", header);
            assertEquals(401, response.getStatus(), String.valueOf(header));
            assertNull(response.getForwardedUrl());
        }
        var ok = call(TOKEN, "/actuator/prometheus", "Bearer " + TOKEN);
        assertEquals(200, ok.getStatus());
        assertNotNull(ok.getForwardedUrl());
    }

    @Test
    void leavesOtherPathsAlone() throws Exception {
        assertEquals(200, call(TOKEN, "/actuator/health", null).getStatus());
    }
}
