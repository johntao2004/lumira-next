package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.ClientAddressResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

class PasskeyChallengeBudgetTest {
    @Test
    void rejectsIssuanceBeforeCreatingAnHttpSession() {
        PasskeyRateLimiter limiter = mock(PasskeyRateLimiter.class);
        when(limiter.allow("198.51.100.18", null, false)).thenReturn(false);
        PasskeyService service = service(limiter);
        var request = request();

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.authenticationOptions(request));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatusCode());
        assertEquals("passkeyRateLimited", error.getReason());
        assertNull(request.getSession(false));
        verify(limiter).allow("198.51.100.18", null, false);
    }

    @Test
    void chargesExistingSessionAndVerificationBudgetBeforeParsingTheAssertion() {
        PasskeyRateLimiter limiter = mock(PasskeyRateLimiter.class);
        when(limiter.allow("198.51.100.18", "session-verified", true)).thenReturn(false);
        PasskeyService service = service(limiter);
        MockHttpServletRequest request = request();
        request.setSession(
                new org.springframework.mock.web.MockHttpSession(null, "session-verified"));

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.completeAuthentication(
                                        null, request, new MockHttpServletResponse()));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatusCode());
        assertEquals("passkeyRateLimited", error.getReason());
        assertEquals("session-verified", request.getSession(false).getId());
        verify(limiter).allow("198.51.100.18", "session-verified", true);
    }

    private static PasskeyService service(PasskeyRateLimiter limiter) {
        return new PasskeyService(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ClientAddressResolver(""),
                null,
                limiter,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static MockHttpServletRequest request() {
        var request =
                new MockHttpServletRequest("POST", "/api/auth/passkeys/authentication/options");
        request.setRemoteAddr("198.51.100.18");
        return request;
    }
}
