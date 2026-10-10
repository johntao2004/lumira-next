package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.AuthenticationSession;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import com.lumira.platform.identity.api.SecondFactorSession;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class CurrentAuthorityFactorVersionTest {
    private static final UUID PRIMARY_EPOCH = new UUID(0, 0);
    private static final UUID FACTOR_EPOCH = new UUID(0, 0);
    private static final String CONTEXT_QUERY =
            """
            SELECT u.id,a.authority FROM platform_user u
              LEFT JOIN platform_effective_authority a ON a.user_id=u.id
             WHERE u.username=:name AND u.enabled=true AND u.deleted_at IS NULL
            """;
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String CONTEXT_REVISION = "d32f39a4-7e85-41ef-9295-f810980501bb";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void policyRevisionChangeRefreshesSessionWhenItsEvidenceStillMeetsCurrentPolicy()
            throws Exception {
        UUID newRevision = UUID.randomUUID();
        var request = authenticatedRequest("PASSWORD", List.of());
        request.addHeader(RolePreviewContextService.REVISION_HEADER, CONTEXT_REVISION);
        request.getSession()
                .setAttribute(
                        AuthenticationSession.AUTHENTICATION_REVISION,
                        UUID.randomUUID().toString());
        var response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);
        var observedSnapshot = new AtomicReference<AuthenticationRequestContext.Snapshot>();
        doAnswer(
                        invocation -> {
                            observedSnapshot.set(AuthenticationRequestContext.current());
                            return null;
                        })
                .when(chain)
                .doFilter(request, response);
        var policies = mock(EffectiveAuthenticationPolicyPort.class);
        when(policies.resolve(USER_ID)).thenReturn(policy(newRevision, false));

        filter(policies).doFilterInternal(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals(
                newRevision.toString(),
                request.getSession().getAttribute(AuthenticationSession.AUTHENTICATION_REVISION));
        assertEquals(
                newRevision.toString(),
                request.getSession()
                        .getAttribute(SecondFactorSession.REQUIREMENT_VERSION_ATTRIBUTE));
        assertEquals(
                List.of(new SimpleGrantedAuthority("platform:users:read")),
                SecurityContextHolder.getContext().getAuthentication().getAuthorities());
        var requestSnapshot = observedSnapshot.get();
        assertEquals("alice", requestSnapshot.principalName());
        assertEquals(USER_ID, requestSnapshot.userId());
        assertEquals(newRevision, requestSnapshot.policy().revision());
        assertNull(AuthenticationRequestContext.current());
        verify(chain).doFilter(request, response);
    }

    @Test
    void newlyRequiredMfaInvalidatesSessionWithoutVerifiedFactorEvidence() throws Exception {
        var request = authenticatedRequest("PASSWORD", List.of());
        request.addHeader(RolePreviewContextService.REVISION_HEADER, CONTEXT_REVISION);
        var response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);
        var policies = mock(EffectiveAuthenticationPolicyPort.class);
        when(policies.resolve(USER_ID)).thenReturn(policy(UUID.randomUUID(), true));

        filter(policies).doFilterInternal(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(AuthenticationRequestContext.current());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void sessionWithVerifiedMfaAndCurrentPrimaryMethodKeepsRefreshedAuthorities() throws Exception {
        UUID revision = UUID.randomUUID();
        var request = authenticatedRequest("PASSKEY", List.of("RECOVERY_CODE"));
        request.addHeader(RolePreviewContextService.REVISION_HEADER, CONTEXT_REVISION);
        request.getSession()
                .setAttribute(AuthenticationSession.AUTHENTICATION_REVISION, revision.toString());
        var response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);
        var policies = mock(EffectiveAuthenticationPolicyPort.class);
        when(policies.resolve(USER_ID)).thenReturn(policy(revision, true));

        filter(policies).doFilterInternal(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals(
                List.of(new SimpleGrantedAuthority("platform:users:read")),
                SecurityContextHolder.getContext().getAuthentication().getAuthorities());
        verify(chain).doFilter(request, response);
    }

    @Test
    void methodNoLongerAllowedInvalidatesTheSession() throws Exception {
        var request = authenticatedRequest("PASSKEY", List.of());
        request.addHeader(RolePreviewContextService.REVISION_HEADER, CONTEXT_REVISION);
        var response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);
        var policies = mock(EffectiveAuthenticationPolicyPort.class);
        var restricted =
                new EffectiveAuthenticationPolicyPort.EffectivePolicy(
                        UUID.randomUUID(),
                        Set.of(PrimaryMethod.PASSWORD),
                        Set.of(PrimaryMethod.PASSWORD),
                        false,
                        List.of(),
                        false);
        when(policies.resolve(USER_ID)).thenReturn(restricted);

        filter(policies).doFilterInternal(request, response, chain);

        assertEquals(401, response.getStatus());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void credentialEpochChangeInvalidatesEvenWhenTheMethodAndMfaEvidenceStillLookUsable()
            throws Exception {
        var request = authenticatedRequest("PASSWORD", List.of("TOTP"));
        request.addHeader(RolePreviewContextService.REVISION_HEADER, CONTEXT_REVISION);
        var response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);
        var policies = mock(EffectiveAuthenticationPolicyPort.class);
        when(policies.resolve(USER_ID))
                .thenReturn(
                        new EffectiveAuthenticationPolicyPort.EffectivePolicy(
                                UUID.randomUUID(),
                                Set.of(PrimaryMethod.PASSWORD, PrimaryMethod.PASSKEY),
                                Set.of(PrimaryMethod.PASSWORD, PrimaryMethod.PASSKEY),
                                true,
                                List.of(),
                                false,
                                UUID.randomUUID(),
                                FACTOR_EPOCH));

        filter(policies).doFilterInternal(request, response, chain);

        assertEquals(401, response.getStatus());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void legacySessionWithoutProofEpochMustReauthenticateInsteadOfBeingUpgraded() throws Exception {
        var request = authenticatedRequest("PASSWORD", List.of());
        request.addHeader(RolePreviewContextService.REVISION_HEADER, CONTEXT_REVISION);
        request.getSession().removeAttribute(AuthenticationSession.PRIMARY_CREDENTIAL_REVISION);
        var response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);
        var policies = mock(EffectiveAuthenticationPolicyPort.class);
        when(policies.resolve(USER_ID)).thenReturn(policy(UUID.randomUUID(), false));

        filter(policies).doFilterInternal(request, response, chain);

        assertEquals(401, response.getStatus());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void requestAuthorizationSnapshotIsClearedWhenTheHandlerThrows() throws Exception {
        var request = authenticatedRequest("PASSWORD", List.of());
        request.addHeader(RolePreviewContextService.REVISION_HEADER, CONTEXT_REVISION);
        var response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);
        var policies = mock(EffectiveAuthenticationPolicyPort.class);
        when(policies.resolve(USER_ID)).thenReturn(policy(UUID.randomUUID(), false));
        doAnswer(
                        invocation -> {
                            assertEquals(
                                    "alice",
                                    AuthenticationRequestContext.current().principalName());
                            throw new jakarta.servlet.ServletException("handler failed");
                        })
                .when(chain)
                .doFilter(request, response);

        assertThrows(
                jakarta.servlet.ServletException.class,
                () -> filter(policies).doFilterInternal(request, response, chain));

        assertNull(AuthenticationRequestContext.current());
    }

    private static MockHttpServletRequest authenticatedRequest(
            String method, List<String> factors) {
        var request = new MockHttpServletRequest("GET", "/api/platform/users");
        var session = request.getSession();
        session.setAttribute(AuthenticationSession.PRIMARY_METHOD, method);
        session.setAttribute(AuthenticationSession.VERIFIED_FACTORS, factors);
        session.setAttribute(
                AuthenticationSession.PRIMARY_CREDENTIAL_REVISION, PRIMARY_EPOCH.toString());
        session.setAttribute(
                AuthenticationSession.FACTOR_CREDENTIAL_REVISION, FACTOR_EPOCH.toString());
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                "alice", null, List.of()));
        return request;
    }

    private static EffectiveAuthenticationPolicyPort.EffectivePolicy policy(
            UUID revision, boolean mfaRequired) {
        return new EffectiveAuthenticationPolicyPort.EffectivePolicy(
                revision,
                Set.of(PrimaryMethod.PASSWORD, PrimaryMethod.PASSKEY),
                Set.of(PrimaryMethod.PASSWORD, PrimaryMethod.PASSKEY),
                mfaRequired,
                List.of(),
                false,
                PRIMARY_EPOCH,
                FACTOR_EPOCH);
    }

    private static CurrentAuthorityFilter filter(EffectiveAuthenticationPolicyPort policies) {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(CONTEXT_QUERY)).thenReturn(statement);
        when(statement.param(eq("name"), eq("alice"))).thenReturn(statement);
        doAnswer(
                        invocation -> {
                            RowCallbackHandler callback = invocation.getArgument(0);
                            var row = mock(java.sql.ResultSet.class);
                            when(row.getObject("id", UUID.class)).thenReturn(USER_ID);
                            when(row.getString("authority")).thenReturn("platform:users:read");
                            callback.processRow(row);
                            return null;
                        })
                .when(statement)
                .query(any(RowCallbackHandler.class));
        var lock = mock(AuthenticationPolicyWriteLock.class);
        var transactions = mock(TransactionTemplate.class);
        var rolePreviewContexts = mock(RolePreviewContextService.class);
        org.mockito.Mockito.when(rolePreviewContexts.resolve(any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            var req =
                                    invocation.getArgument(
                                            0, jakarta.servlet.http.HttpServletRequest.class);
                            return new RequestAuthorizationContext(
                                    invocation.getArgument(1),
                                    invocation.getArgument(2),
                                    req.getSession(false).getId(),
                                    Set.of("platform:users:read"),
                                    Set.of(),
                                    java.util.Map.of(),
                                    Set.of("platform:users:read"),
                                    Set.of(),
                                    java.util.Map.of(),
                                    CONTEXT_REVISION,
                                    req.getHeader(RolePreviewContextService.REVISION_HEADER),
                                    true,
                                    null);
                        });
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            var req =
                                    invocation.getArgument(
                                            0, jakarta.servlet.http.HttpServletRequest.class);
                            if (!CONTEXT_REVISION.equals(
                                    req.getHeader(RolePreviewContextService.REVISION_HEADER)))
                                throw new org.springframework.web.server.ResponseStatusException(
                                        org.springframework.http.HttpStatus.CONFLICT,
                                        "authorizationContextChanged");
                            return null;
                        })
                .when(rolePreviewContexts)
                .enforceRequestBoundary(any(), any());
        when(transactions.execute(any(TransactionCallback.class)))
                .thenAnswer(
                        invocation -> {
                            TransactionCallback<?> callback = invocation.getArgument(0);
                            return callback.doInTransaction(mock(TransactionStatus.class));
                        });
        return new CurrentAuthorityFilter(jdbc, policies, lock, transactions, rolePreviewContexts);
    }
}
