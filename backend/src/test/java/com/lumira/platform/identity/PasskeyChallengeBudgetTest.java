package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.identity.api.LoginResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

class PasskeyChallengeBudgetTest {
    @Test
    void passkeyLoginPreservesTheForcedPasswordChangeState() {
        UUID challengeId = UUID.randomUUID();
        var forced =
                new LoginResponse(
                        false,
                        false,
                        challengeId,
                        Instant.parse("2026-10-11T10:30:00Z"),
                        "PASSWORD_CHANGE_REQUIRED",
                        UUID.randomUUID().toString(),
                        List.of(),
                        "/login/password-change");

        Map<String, Object> response = PasskeyService.loginResponseBody(forced);

        assertEquals(false, response.get("authenticated"));
        assertEquals(false, response.get("secondFactorRequired"));
        assertEquals(challengeId, response.get("challengeId"));
        assertEquals(forced.expiresAt(), response.get("expiresAt"));
        assertEquals("PASSWORD_CHANGE_REQUIRED", response.get("state"));
        assertEquals(forced.policyRevision(), response.get("policyRevision"));
        assertEquals(List.of(), response.get("factors"));
        assertEquals("/login/password-change", response.get("nextPath"));
    }

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
