package com.lumira.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.identity.AuthenticationPolicyService;
import com.lumira.platform.identity.api.AccountOwnerPort;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort.EffectivePolicy;
import com.lumira.platform.modules.ModuleCapabilityPort;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class PlatformControllerSessionTest {
    @Test
    void meReturnsStableServerUserIdForTheAuthenticatedAccount() {
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
        when(owners.id("alice", false)).thenReturn(userId);
        when(policies.resolve(userId))
                .thenReturn(
                        new EffectivePolicy(
                                UUID.randomUUID(), Set.of(), Set.of(), false, List.of(), false));

        var authentication =
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());
        var result =
                new PlatformController(jdbc, modules, policies, owners)
                        .me(authentication, new MockHttpServletRequest());

        assertEquals(userId, result.get("userId"));
        assertEquals("alice", result.get("username"));
        assertEquals("Alice", result.get("displayName"));
    }
}
