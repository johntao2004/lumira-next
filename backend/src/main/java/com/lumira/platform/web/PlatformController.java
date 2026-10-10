package com.lumira.platform.web;

import com.lumira.platform.identity.api.AccountOwnerPort;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.AuthenticationSession;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("platform")
public class PlatformController {
    private final JdbcClient jdbc;
    private final com.lumira.platform.modules.ModuleCapabilityPort modules;
    private final EffectiveAuthenticationPolicyPort authenticationPolicies;
    private final AccountOwnerPort owners;

    public PlatformController(
            JdbcClient jdbc,
            com.lumira.platform.modules.ModuleCapabilityPort modules,
            EffectiveAuthenticationPolicyPort authenticationPolicies,
            AccountOwnerPort owners) {
        this.jdbc = jdbc;
        this.modules = modules;
        this.authenticationPolicies = authenticationPolicies;
        this.owners = owners;
    }

    @GetMapping("/api/auth/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @GetMapping("/api/auth/me")
    public Map<String, Object> me(Authentication auth, HttpServletRequest request) {
        String displayName =
                jdbc.sql(
                                "SELECT display_name FROM platform_user WHERE username=:username AND enabled=true")
                        .param("username", auth.getName())
                        .query(String.class)
                        .optional()
                        .orElseThrow(
                                () ->
                                        new org.springframework.web.server.ResponseStatusException(
                                                org.springframework.http.HttpStatus.UNAUTHORIZED));
        var session = request.getSession(false);
        var validated = AuthenticationRequestContext.current();
        boolean currentValidatedRequest =
                validated != null && validated.principalName().equals(auth.getName());
        var userId =
                currentValidatedRequest ? validated.userId() : owners.id(auth.getName(), false);
        var effective =
                currentValidatedRequest
                        ? validated.policy()
                        : authenticationPolicies.resolve(userId);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put(
                "primaryMethod",
                session == null
                        ? null
                        : session.getAttribute(AuthenticationSession.PRIMARY_METHOD));
        evidence.put(
                "verifiedFactors",
                AuthenticationSession.factors(
                        session == null
                                ? null
                                : session.getAttribute(AuthenticationSession.VERIFIED_FACTORS)));
        evidence.put(
                "authenticatedAt",
                session == null
                        ? null
                        : session.getAttribute(AuthenticationSession.AUTHENTICATED_AT));
        evidence.put(
                "authenticationRevision",
                session == null
                        ? null
                        : session.getAttribute(AuthenticationSession.AUTHENTICATION_REVISION));
        var effectiveView = new LinkedHashMap<String, Object>();
        effectiveView.put("allowedMethods", effective.allowedMethods());
        effectiveView.put("usableMethods", effective.usableMethods());
        effectiveView.put("mfaRequired", effective.mfaRequired());
        effectiveView.put("requirementSources", effective.requirementSources());
        var requestAuthorization = currentValidatedRequest ? validated.authorization() : null;
        var authorizationContext = new LinkedHashMap<String, Object>();
        authorizationContext.put(
                "revision", requestAuthorization == null ? null : requestAuthorization.revision());
        authorizationContext.put(
                "rolePreviewSupported",
                requestAuthorization != null && requestAuthorization.rolePreviewSupported());
        authorizationContext.put(
                "preview",
                requestAuthorization == null || !requestAuthorization.rolePreviewSupported()
                        ? null
                        : requestAuthorization.preview());
        var result = new LinkedHashMap<String, Object>();
        result.put("enabledModules", modules.enabledModules());
        result.put("username", auth.getName());
        result.put("userId", userId);
        result.put("displayName", displayName);
        result.put(
                "globalAuthorities",
                requestAuthorization == null
                        ? jdbc.sql(
                                        "SELECT a.authority FROM platform_global_authority a JOIN platform_user u ON u.id=a.user_id WHERE u.username=:username AND u.enabled ORDER BY a.authority")
                                .param("username", auth.getName())
                                .query(String.class)
                                .list()
                        : requestAuthorization.effectiveGlobalAuthorities().stream()
                                .sorted()
                                .toList());
        result.put(
                "authorities",
                requestAuthorization == null
                        ? auth.getAuthorities().stream().map(a -> a.getAuthority()).toList()
                        : requestAuthorization.effectiveAuthorities().stream().sorted().toList());
        result.put("authenticationEvidence", evidence);
        result.put("effectiveAuthentication", effectiveView);
        result.put("authorizationContext", authorizationContext);
        return result;
    }
}
