package com.lumira.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AuthorizationRevisionPort;
import com.lumira.platform.identity.AuthenticationPolicyService;
import com.lumira.platform.identity.api.AccountOwnerPort;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort.EffectivePolicy;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort.RequirementSource;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import com.lumira.platform.modules.ModuleCapabilityPort;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class PlatformControllerAuthenticationContextTest {
    @Test
    void meUsesOnlyTheCurrentRequestValidatedPolicySnapshot() {
        var jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(
                                "SELECT display_name FROM platform_user WHERE username=:username AND enabled=true")
                        .param("username", "alice")
                        .query(String.class)
                        .optional())
                .thenReturn(java.util.Optional.of("Alice"));
        when(jdbc.sql(
                                "SELECT a.authority FROM platform_global_authority a JOIN platform_user u ON u.id=a.user_id WHERE u.username=:username AND u.enabled ORDER BY a.authority")
                        .param("username", "alice")
                        .query(String.class)
                        .list())
                .thenReturn(List.of("platform:settings:read"));
        var modules = mock(ModuleCapabilityPort.class);
        when(modules.enabledModules()).thenReturn(List.of("identity"));
        var policies = mock(AuthenticationPolicyService.class);
        var owners = mock(AccountOwnerPort.class);
        UUID userId = UUID.randomUUID();
        var authorizationRevisions = mock(AuthorizationRevisionPort.class);
        when(authorizationRevisions.revision(
                        userId, Set.of("platform:users:read"), Set.of("platform:roles:read")))
                .thenReturn("session-authorization-tag");
        var policy =
                new EffectivePolicy(
                        UUID.randomUUID(),
                        Set.of(),
                        Set.of(),
                        true,
                        List.of(new RequirementSource("GLOBAL", null)),
                        false);
        var request = new MockHttpServletRequest();
        var authorization =
                new RequestAuthorizationContext(
                        "alice",
                        userId,
                        UUID.randomUUID().toString(),
                        Set.of("platform:users:read"),
                        Set.of("platform:roles:read"),
                        java.util.Map.of(),
                        Set.of("platform:users:read"),
                        Set.of("platform:roles:read"),
                        java.util.Map.of(),
                        "authorization-revision",
                        "authorization-revision",
                        true,
                        null);
        try (var ignored =
                AuthenticationRequestContext.bind(
                        new AuthenticationRequestContext.Snapshot(
                                "alice", userId, policy, authorization))) {
            var authentication =
                    UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());

            var result =
                    new PlatformController(jdbc, modules, policies, owners, authorizationRevisions)
                            .me(authentication, request);

            assertEquals(
                    Set.of(),
                    ((java.util.Map<?, ?>) result.get("effectiveAuthentication"))
                            .get("allowedMethods"));
            assertEquals(
                    true,
                    ((java.util.Map<?, ?>) result.get("effectiveAuthentication"))
                            .get("mfaRequired"));
            assertEquals(List.of("platform:users:read"), result.get("authorities"));
            assertEquals(List.of("platform:roles:read"), result.get("globalAuthorities"));
            assertEquals(userId, result.get("userId"));
            assertNotNull(result.get("authorizationRevision"));
            assertEquals("session-authorization-tag", result.get("authorizationRevision"));
            var authorizationContext = (java.util.Map<?, ?>) result.get("authorizationContext");
            assertEquals("authorization-revision", authorizationContext.get("revision"));
            assertEquals(true, authorizationContext.get("rolePreviewSupported"));
            verifyNoInteractions(policies, owners);
        }
    }
}
