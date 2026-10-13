package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.identity.api.LoginResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.ObjectMapper;

class PasswordLoginFilterTest {
    @Test
    void passwordHashCapacityIsReleasedAfterAuthenticationFailure() {
        var delegate =
                mock(org.springframework.security.authentication.AuthenticationProvider.class);
        when(delegate.supports(any())).thenReturn(true);
        when(delegate.authenticate(any()))
                .thenThrow(
                        new org.springframework.security.authentication.BadCredentialsException(
                                "bad"));
        var provider =
                new PasswordProofAuthenticationProvider(delegate, new PasswordCredentialProof());
        var authentication = UsernamePasswordAuthenticationToken.unauthenticated("alice", "secret");

        assertTrue(PasswordHashCapacity.tryAcquire());
        assertTrue(PasswordHashCapacity.tryAcquire());
        try {
            assertInstanceOf(
                    PasswordProofAuthenticationProvider.CapacityException.class,
                    assertThrows(
                            org.springframework.security.core.AuthenticationException.class,
                            () -> provider.authenticate(authentication)));
        } finally {
            PasswordHashCapacity.release();
            PasswordHashCapacity.release();
        }

        assertThrows(
                org.springframework.security.authentication.BadCredentialsException.class,
                () -> provider.authenticate(authentication));
        assertTrue(PasswordHashCapacity.tryAcquire());
        assertTrue(PasswordHashCapacity.tryAcquire());
        PasswordHashCapacity.release();
        PasswordHashCapacity.release();
    }

    @Test
    void httpPasswordFilterReceivesOnlyErasedPrincipalAndRequestBoundProof() throws Exception {
        var encoder = new BCryptPasswordEncoder(10);
        var hash = encoder.encode("correct horse battery");
        var revision = UUID.randomUUID();
        var proof = new PasswordCredentialProof();
        var details =
                new PasswordProofUserDetails(
                        "alice",
                        hash,
                        true,
                        revision,
                        List.of(new SimpleGrantedAuthority("platform:users:read")));
        UserDetailsPasswordService upgrades =
                (user, newPassword) ->
                        new PasswordProofUserDetails(
                                user.getUsername(),
                                newPassword,
                                user.isEnabled(),
                                revision,
                                user.getAuthorities());
        var provider =
                new IdentityConfiguration()
                        .authenticationProvider(username -> details, encoder, upgrades, proof);
        var manager = new ProviderManager(List.of(provider));
        var flow = mock(SecondFactorLoginService.class);
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setParameter("username", "alice");
        request.setParameter("password", "correct horse battery");
        var response = new MockHttpServletResponse();
        when(flow.primary(any(), eq(request), eq(response))).thenReturn(LoginResponse.complete());
        var filter = new PasswordLoginFilter(flow, new ObjectMapper());
        filter.setAuthenticationManager(manager);

        filter.doFilter(request, response, new MockFilterChain());

        var captured =
                org.mockito.ArgumentCaptor.forClass(
                        org.springframework.security.core.Authentication.class);
        verify(flow).primary(captured.capture(), eq(request), eq(response));
        assertEquals(200, response.getStatus());
        assertNull(
                ((org.springframework.security.core.userdetails.UserDetails)
                                captured.getValue().getPrincipal())
                        .getPassword());
        assertInstanceOf(PasswordCredentialProof.Evidence.class, captured.getValue().getDetails());
        var evidence = (PasswordCredentialProof.Evidence) captured.getValue().getDetails();
        assertNotEquals(hash, evidence.hashFingerprint());
        assertTrue(proof.matches(evidence.hashFingerprint(), hash));
        assertEquals(revision, evidence.primaryCredentialRevision());
    }

    @Test
    void alwaysRequiredCaptchaWithoutProofIsRejectedBeforePasswordAuthentication()
            throws Exception {
        var proofPort = mock(CaptchaProofPort.class);
        when(proofPort.requirement(
                        eq(CaptchaProofPort.Action.PASSWORD_LOGIN),
                        isNull(),
                        isNull(),
                        any(CaptchaProofPort.RequestFacts.class)))
                .thenReturn(
                        new CaptchaProofPort.Requirement(
                                true, "always-policy-revision", "IMAGE_TEXT"));
        when(proofPort.readyForRequiredPolicy()).thenReturn(true);
        @SuppressWarnings("unchecked")
        var providers = (ObjectProvider<CaptchaProofPort>) mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(proofPort);
        var crypto = mock(PublicAuthCrypto.class);
        when(crypto.sourceDigest(anyString())).thenReturn("source-digest");
        var captcha = new CaptchaProofAccess(providers, new ClientAddressResolver(""), crypto);
        var manager = mock(AuthenticationManager.class);
        var filter =
                new PasswordLoginFilter(
                        mock(SecondFactorLoginService.class), new ObjectMapper(), captcha);
        filter.setAuthenticationManager(manager);
        filter.setAuthenticationFailureHandler(
                (request, response, error) -> {
                    var captchaError =
                            assertInstanceOf(
                                    PasswordLoginFilter.CaptchaAuthenticationException.class,
                                    error);
                    response.setStatus(captchaError.status());
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"detail\":\"" + captchaError.code() + "\"}");
                });

        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setParameter("username", "alice");
        request.setParameter("password", "correct horse battery");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(400, response.getStatus());
        assertEquals("{\"detail\":\"captchaRequired\"}", response.getContentAsString());
        verify(proofPort)
                .requirement(
                        eq(CaptchaProofPort.Action.PASSWORD_LOGIN),
                        isNull(),
                        isNull(),
                        any(CaptchaProofPort.RequestFacts.class));
        verifyNoInteractions(manager);
    }

    @Test
    void primaryAuthenticationDoesNotPublishAContext() throws Exception {
        SecurityContextHolder.clearContext();
        var flow = mock(SecondFactorLoginService.class);
        var req = new MockHttpServletRequest();
        var res = new MockHttpServletResponse();
        var auth =
                UsernamePasswordAuthenticationToken.authenticated(
                        "fixture", null, java.util.List.of());
        when(flow.primary(auth, req, res))
                .thenReturn(new LoginResponse(false, true, UUID.randomUUID(), Instant.now()));
        new PasswordLoginFilter(flow, new ObjectMapper())
                .successfulAuthentication(req, res, new MockFilterChain(), auth);
        assertEquals(202, res.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(req.getSession(false));
        assertTrue(res.getContentAsString().contains("secondFactorRequired"));
    }

    @Test
    void unavailableProviderNeverLeavesAnAuthenticatedSession() throws Exception {
        var flow = mock(SecondFactorLoginService.class);
        var req = new MockHttpServletRequest();
        var res = new MockHttpServletResponse();
        req.getSession();
        var auth =
                UsernamePasswordAuthenticationToken.authenticated(
                        "fixture", null, java.util.List.of());
        doThrow(
                        new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                                "secondFactorUnavailable"))
                .when(flow)
                .primary(auth, req, res);
        new PasswordLoginFilter(flow, new ObjectMapper())
                .successfulAuthentication(req, res, new MockFilterChain(), auth);
        assertEquals(503, res.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(res.getContentAsString().contains("secondFactorUnavailable"));
    }
}
