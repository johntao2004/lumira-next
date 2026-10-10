package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.lumira.platform.identity.api.AuthenticationSession;
import com.lumira.platform.identity.api.SecondFactorSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class WechatCallbackFailureTest {
    @Test
    void clearsOnlyTheFailedAuthenticationSessionAndItsPendingEvidence() {
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        request.setSession(session);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "user", null, java.util.List.of()));
        SecurityContextHolder.setContext(context);
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        session.setAttribute(AuthenticationSession.PRIMARY_METHOD, "WECHAT");
        session.setAttribute(AuthenticationSession.VERIFIED_FACTORS, java.util.List.of());
        session.setAttribute(AuthenticationSession.AUTHENTICATED_AT, java.time.Instant.now());
        session.setAttribute(AuthenticationSession.AUTHENTICATION_REVISION, "revision");
        session.setAttribute(AuthenticationSession.PRIMARY_CREDENTIAL_REVISION, "primary-revision");
        session.setAttribute(AuthenticationSession.FACTOR_CREDENTIAL_REVISION, "factor-revision");
        session.setAttribute(SecondFactorSession.REQUIREMENT_VERSION_ATTRIBUTE, "requirement");
        session.setAttribute("lumira.login.challenge", "pending-challenge");

        try {
            WechatIdentityService.clearCurrentAuthenticationSession(request);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
            assertThrows(
                    IllegalStateException.class,
                    () -> session.getAttribute(AuthenticationSession.PRIMARY_METHOD));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void callbackStillRedirectsToTheFixedResultPageAfterUnexpectedServiceFailure()
            throws Exception {
        var service = mock(WechatIdentityService.class);
        doThrow(new IllegalStateException("must not be exposed"))
                .when(service)
                .callback(
                        any(),
                        any(),
                        any(),
                        any(HttpServletRequest.class),
                        any(HttpServletResponse.class));
        var controller = new WechatController(service);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        assertDoesNotThrow(
                () -> controller.callback("state-secret", "code-secret", null, request, response));

        assertEquals(303, response.getStatus());
        assertEquals("/user/wechat-result", response.getHeader("Location"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
    }
}
