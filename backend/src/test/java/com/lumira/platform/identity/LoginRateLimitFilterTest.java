package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.ClientAddressResolver;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LoginRateLimitFilterTest {
    static class StubLimiter extends LoginRateLimiter {
        String account, source;
        boolean allowed;

        StubLimiter(boolean allowed) {
            super(null, 1, 1);
            this.allowed = allowed;
        }

        @Override
        public boolean allow(String account, String source) {
            this.account = account;
            this.source = source;
            return allowed;
        }
    }

    @Test
    void forwardedHeaderCannotChangeRateLimitIdentity() throws Exception {
        var limiter = new StubLimiter(false);
        var reached = new AtomicBoolean();
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setServletPath("/api/auth/login");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.55");
        request.addParameter("username", "Example");
        request.addParameter("password", "example-password");
        var response = new MockHttpServletResponse();
        new LoginRateLimitFilter(limiter, new ClientAddressResolver(""))
                .doFilter(request, response, (req, res) -> reached.set(true));
        assertEquals("example", limiter.account);
        assertEquals("127.0.0.1", limiter.source);
        assertFalse(reached.get());
        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
    }

    @Test
    void oversizedPasswordNeverReachesAuthentication() throws Exception {
        var limiter = new StubLimiter(true);
        var reached = new AtomicBoolean();
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setServletPath("/api/auth/login");
        request.addParameter("username", "example");
        request.addParameter("password", "密".repeat(86));
        var response = new MockHttpServletResponse();
        new LoginRateLimitFilter(limiter, new ClientAddressResolver(""))
                .doFilter(request, response, (req, res) -> reached.set(true));
        assertFalse(reached.get());
        assertEquals(401, response.getStatus());
    }

    @Test
    void knownAccountRejectionsAreAuditedWithoutChangingFailClosedResponses() throws Exception {
        var limiter = new StubLimiter(false);
        var audit = mock(LoginAudit.class);
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setServletPath("/api/auth/login");
        request.setRemoteAddr("192.0.2.9");
        request.addHeader("User-Agent", "QA browser");
        request.addParameter("username", "Example");
        request.addParameter("password", "example-password");
        var response = new MockHttpServletResponse();
        new LoginRateLimitFilter(limiter, new ClientAddressResolver(""), audit)
                .doFilter(
                        request,
                        response,
                        (req, res) -> fail("rate limited request reached authentication"));
        assertEquals(429, response.getStatus());
        verify(audit).record("example", "account.loginRateLimited", "192.0.2.9", "QA browser");

        limiter.allowed = true;
        request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setServletPath("/api/auth/login");
        request.setRemoteAddr("192.0.2.9");
        request.addHeader("User-Agent", "QA browser");
        request.addParameter("username", "Example");
        request.addParameter("password", "密".repeat(86));
        response = new MockHttpServletResponse();
        new LoginRateLimitFilter(limiter, new ClientAddressResolver(""), audit)
                .doFilter(
                        request,
                        response,
                        (req, res) -> fail("oversized password reached authentication"));
        assertEquals(401, response.getStatus());
        verify(audit).record("example", "account.loginRejected", "192.0.2.9", "QA browser");
    }

    @Test
    void otherEndpointsDoNotConsumeLoginQuota() throws Exception {
        var limiter = new StubLimiter(false);
        var reached = new AtomicBoolean();
        var request = new MockHttpServletRequest("GET", "/api/auth/me");
        var response = new MockHttpServletResponse();
        new LoginRateLimitFilter(limiter, new ClientAddressResolver(""))
                .doFilter(request, response, (req, res) -> reached.set(true));
        assertNull(limiter.account);
        assertTrue(reached.get());
    }
}
