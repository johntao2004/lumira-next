package com.lumira.platform.identity;

import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.AuthenticationSession;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import com.lumira.platform.identity.api.SecondFactorSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/** Re-evaluates live grants and authentication policy for every authenticated request. */
final class CurrentAuthorityFilter extends OncePerRequestFilter {
    // Materialize only the unique actor. The scalar id lets PostgreSQL push the
    // predicate into both grant-view branches instead of aggregating all users.
    static final String CONTEXT_QUERY =
            """
            WITH actor AS NOT MATERIALIZED (
                SELECT u.id,u.primary_credential_revision,u.factor_credential_revision,
                       (u.password_hash IS NOT NULL) AS password_present,
                       EXISTS(SELECT 1 FROM platform_passkey_credential p WHERE p.user_id=u.id) AS passkey_present
                  FROM platform_user u
                 WHERE u.username=:name AND u.enabled=true AND u.deleted_at IS NULL
            )
            SELECT u.id,a.authority,u.primary_credential_revision,u.factor_credential_revision,
                   u.password_present,u.passkey_present FROM actor u
              LEFT JOIN LATERAL (
                SELECT a.authority FROM platform_effective_authority a WHERE a.user_id=u.id
              ) a ON true
            """;

    private final JdbcClient jdbc;
    private final EffectiveAuthenticationPolicyPort policies;
    private final AuthenticationPolicyWriteLock policyLock;
    private final TransactionTemplate transactions;
    private final RolePreviewContextService rolePreviewContexts;

    private record CurrentContext(
            UUID userId,
            List<SimpleGrantedAuthority> grants,
            EffectiveAuthenticationPolicyPort.EffectivePolicy policy,
            RequestAuthorizationContext authorization) {}

    CurrentAuthorityFilter(
            JdbcClient jdbc,
            EffectiveAuthenticationPolicyPort policies,
            AuthenticationPolicyWriteLock policyLock,
            TransactionTemplate transactions,
            RolePreviewContextService rolePreviewContexts) {
        this.jdbc = jdbc;
        this.policies = policies;
        this.policyLock = policyLock;
        this.transactions = transactions;
        this.rolePreviewContexts = rolePreviewContexts;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/");
    }

    private CurrentContext buildCurrentContext(
            HttpServletRequest request,
            String username,
            UUID userId,
            EffectiveAuthenticationPolicyPort.EffectivePolicy policy,
            Set<String> effAuths,
            Set<String> globAuths,
            String noneSessionPrimaryId,
            UUID noneRevision) {
        RequestAuthorizationContext authorization;
        if (noneSessionPrimaryId != null && noneRevision != null && globAuths != null) {
            authorization =
                    rolePreviewContexts.resolvePreloadedNoneContext(
                            request,
                            username,
                            userId,
                            noneSessionPrimaryId,
                            noneRevision,
                            effAuths,
                            globAuths);
        } else if (globAuths != null) {
            authorization =
                    rolePreviewContexts.resolve(request, username, userId, effAuths, globAuths);
        } else {
            authorization = rolePreviewContexts.resolve(request, username, userId, effAuths);
        }
        var grants = effAuths.stream().sorted().map(SimpleGrantedAuthority::new).toList();
        return new CurrentContext(userId, grants, policy, authorization);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var context = SecurityContextHolder.getContext();
        var authentication = context.getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response);
            return;
        }

        var session = request.getSession(false);
        if (session == null) {
            reject(request, response);
            return;
        }

        CurrentContext current =
                transactions.execute(
                        status -> {
                            // Share the write-side policy lock so grants and effective
                            // authentication policy
                            // are read from one coherent point before the request reaches an
                            // application handler.
                            policyLock.lockForAuthentication();

                            if (policies instanceof AuthenticationPolicyService authService
                                    && authService.supportsUnifiedAdmission()) {
                                try {
                                    CurrentContext unified =
                                            authService.supportsUnifiedSessionAdmission()
                                                    ? authService.admitWithSession(
                                                            authentication.getName(),
                                                            session.getId(),
                                                            (userId,
                                                                    credentialEpoch,
                                                                    policy,
                                                                    effAuths,
                                                                    globAuths,
                                                                    noneSessionPrimaryId,
                                                                    noneRevision) ->
                                                                    buildCurrentContext(
                                                                            request,
                                                                            authentication
                                                                                    .getName(),
                                                                            userId,
                                                                            policy,
                                                                            effAuths,
                                                                            globAuths,
                                                                            noneSessionPrimaryId,
                                                                            noneRevision))
                                                    : authService.admit(
                                                            authentication.getName(),
                                                            (userId,
                                                                    credentialEpoch,
                                                                    policy,
                                                                    effAuths,
                                                                    globAuths) ->
                                                                    buildCurrentContext(
                                                                            request,
                                                                            authentication
                                                                                    .getName(),
                                                                            userId,
                                                                            policy,
                                                                            effAuths,
                                                                            globAuths,
                                                                            null,
                                                                            null));
                                    return unified;
                                } catch (DataAccessException
                                        | IllegalArgumentException
                                        | IllegalStateException fallback) {
                                    // Failsafe: on any unexpected schema or mapping failure, fall
                                    // back to sequential reads
                                }
                            }

                            UUID[] userId = {null};
                            AdmissionCredentialProjection.CredentialEpoch[] credentials = {null};
                            var grants = new ArrayList<SimpleGrantedAuthority>();
                            var authorityNames = new HashSet<String>();
                            jdbc.sql(CONTEXT_QUERY)
                                    .param("name", authentication.getName())
                                    .query(
                                            (RowCallbackHandler)
                                                    row -> {
                                                        userId[0] = row.getObject("id", UUID.class);
                                                        if (credentials[0] == null) {
                                                            credentials[0] =
                                                                    new AdmissionCredentialProjection
                                                                            .CredentialEpoch(
                                                                            row.getObject(
                                                                                    "primary_credential_revision",
                                                                                    UUID.class),
                                                                            row.getObject(
                                                                                    "factor_credential_revision",
                                                                                    UUID.class),
                                                                            row.getBoolean(
                                                                                    "password_present"),
                                                                            row.getBoolean(
                                                                                    "passkey_present"));
                                                        }
                                                        String authority =
                                                                row.getString("authority");
                                                        if (authority != null
                                                                && authorityNames.add(authority)) {
                                                            grants.add(
                                                                    new SimpleGrantedAuthority(
                                                                            authority));
                                                        }
                                                    });
                            if (userId[0] == null) return null;
                            var realAuthorities = Set.copyOf(authorityNames);
                            return AdmissionCredentialProjection.within(
                                    userId[0],
                                    credentials[0],
                                    () -> {
                                        var policy = policies.resolve(userId[0]);
                                        Set<String> projectedGlobal =
                                                AdmissionCredentialProjection.getGlobalAuthorities(
                                                        userId[0]);
                                        RequestAuthorizationContext authorization =
                                                projectedGlobal != null
                                                        ? rolePreviewContexts.resolve(
                                                                request,
                                                                authentication.getName(),
                                                                userId[0],
                                                                realAuthorities,
                                                                projectedGlobal)
                                                        : null;
                                        if (authorization == null) {
                                            authorization =
                                                    rolePreviewContexts.resolve(
                                                            request,
                                                            authentication.getName(),
                                                            userId[0],
                                                            realAuthorities);
                                        }
                                        return new CurrentContext(
                                                userId[0],
                                                List.copyOf(grants),
                                                policy,
                                                authorization);
                                    });
                        });
        if (current == null) {
            reject(request, response);
            return;
        }

        var effective = current.policy();
        Object primaryValue = session.getAttribute(AuthenticationSession.PRIMARY_METHOD);
        PrimaryMethod primary = null;
        boolean legacySession = primaryValue == null;
        if (primaryValue instanceof String method) {
            try {
                primary = PrimaryMethod.valueOf(method);
            } catch (IllegalArgumentException invalidEvidence) {
                reject(request, response);
                return;
            }
        } else if (!legacySession) {
            reject(request, response);
            return;
        }

        List<String> factors =
                AuthenticationSession.factors(
                        session.getAttribute(AuthenticationSession.VERIFIED_FACTORS));
        Object primaryCredentialRevision =
                session.getAttribute(AuthenticationSession.PRIMARY_CREDENTIAL_REVISION);
        if (!(primaryCredentialRevision instanceof String primaryEpoch)
                || !primaryEpoch.equals(effective.primaryCredentialRevision().toString())) {
            reject(request, response);
            return;
        }
        if ((effective.mfaRequired() || !factors.isEmpty())
                && (!(session.getAttribute(AuthenticationSession.FACTOR_CREDENTIAL_REVISION)
                                instanceof String factorEpoch)
                        || !factorEpoch.equals(effective.factorCredentialRevision().toString()))) {
            reject(request, response);
            return;
        }
        boolean primaryStillValid =
                primary == null
                        ? effective.usableMethods().contains(PrimaryMethod.PASSWORD)
                                || effective.usableMethods().contains(PrimaryMethod.PASSKEY)
                        : effective.allowedMethods().contains(primary)
                                && effective.usableMethods().contains(primary);
        boolean mfaSatisfied =
                !effective.mfaRequired()
                        || factors.stream()
                                .anyMatch(
                                        factor ->
                                                factor.equals("TOTP")
                                                        || factor.equals("RECOVERY_CODE"));
        if (!primaryStillValid || !mfaSatisfied) {
            reject(request, response);
            return;
        }

        String revision = effective.revision().toString();
        Object priorRevision = session.getAttribute(AuthenticationSession.AUTHENTICATION_REVISION);
        if (legacySession || !revision.equals(priorRevision)) {
            session.setAttribute(AuthenticationSession.AUTHENTICATION_REVISION, revision);
            session.setAttribute(SecondFactorSession.REQUIREMENT_VERSION_ATTRIBUTE, revision);
        }
        var effectiveGrants =
                current.authorization().effectiveAuthorities().stream()
                        .sorted()
                        .map(SimpleGrantedAuthority::new)
                        .toList();
        if (!(authentication instanceof UsernamePasswordAuthenticationToken)
                || !authentication.isAuthenticated()
                || authentication.getCredentials() != null
                || !Objects.equals(authentication.getAuthorities(), effectiveGrants)) {
            var refreshed =
                    UsernamePasswordAuthenticationToken.authenticated(
                            authentication.getPrincipal(), null, effectiveGrants);
            refreshed.setDetails(authentication.getDetails());
            var nextContext = SecurityContextHolder.createEmptyContext();
            nextContext.setAuthentication(refreshed);
            SecurityContextHolder.setContext(nextContext);
        }
        var authorizationScope =
                AuthenticationRequestContext.bind(
                        new AuthenticationRequestContext.Snapshot(
                                authentication.getName(),
                                current.userId(),
                                effective,
                                current.authorization()));
        try (authorizationScope) {
            try {
                rolePreviewContexts.enforceRequestBoundary(request, current.authorization());
            } catch (ResponseStatusException denied) {
                String code = denied.getReason() == null ? "forbidden" : denied.getReason();
                try {
                    rolePreviewContexts.recordBoundaryRejection(current.authorization(), code);
                } catch (RuntimeException ignored) {
                    // The request still fails closed if the audit store is unavailable.
                }
                response.setStatus(denied.getStatusCode().value());
                response.setContentType("application/json;charset=UTF-8");
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"detail\":\"" + code + "\"}");
                return;
            }
            chain.doFilter(request, response);
        }
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(
                    org.springframework.security.web.context.HttpSessionSecurityContextRepository
                            .SPRING_SECURITY_CONTEXT_KEY);
            session.invalidate();
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"detail\":\"reauthenticationRequired\"}");
    }
}
