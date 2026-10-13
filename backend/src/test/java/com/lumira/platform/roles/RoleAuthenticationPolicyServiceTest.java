package com.lumira.platform.roles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.GlobalAuthenticationPolicyPort;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.common.RoleAuthenticationPolicyPort;
import com.lumira.platform.identity.api.AuthenticationMethodAvailabilityPort;
import com.lumira.platform.identity.api.AuthenticationReachabilityPort;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;

class RoleAuthenticationPolicyServiceTest {
    @Test
    void policyEditorCanInitializeTheEditorThroughTheReadEndpoint() {
        String actor = "policy-editor";
        UUID roleId = UUID.randomUUID();
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        @SuppressWarnings("unchecked")
        var accountCount =
                (JdbcClient.MappedQuerySpec<Long>) mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(accountCount);
        when(accountCount.single()).thenReturn(0L);

        var repository = mock(RoleAuthenticationPolicyRepository.class);
        when(repository.forRole(roleId))
                .thenReturn(
                        new RoleAuthenticationPolicyPort.RolePolicy(
                                roleId,
                                1,
                                0,
                                UUID.randomUUID(),
                                RoleAuthenticationPolicyPort.LoginMode.INHERIT,
                                Set.<PrimaryMethod>of(),
                                RoleAuthenticationPolicyPort.MfaMode.INHERIT,
                                RoleAuthenticationPolicyPort.CaptchaMode.INHERIT));

        var guard = mock(AdministrationPort.class);
        when(guard.hasGlobal(actor, PlatformOperation.ROLE_READ_AUTHENTICATION_POLICY.permission()))
                .thenReturn(false);
        when(guard.hasGlobal(
                        actor, PlatformOperation.ROLE_UPDATE_AUTHENTICATION_POLICY.permission()))
                .thenReturn(true);
        @SuppressWarnings("unchecked")
        var captcha = (ObjectProvider<CaptchaProofPort>) mock(ObjectProvider.class);
        var service =
                new RoleAuthenticationPolicyService(
                        jdbc,
                        repository,
                        guard,
                        mock(AuditPort.class),
                        mock(GlobalAuthenticationPolicyPort.class),
                        mock(AuthenticationReachabilityPort.class),
                        mock(AuthenticationPolicyWriteLock.class),
                        mock(PluginExtensionsPort.class),
                        captcha,
                        mock(AuthenticationMethodAvailabilityPort.class));

        assertEquals(roleId, service.read(roleId, actor).roleId());
        verify(guard).requirePageGlobal(actor, PlatformPageId.ROLES);
        verify(guard)
                .hasGlobal(actor, PlatformOperation.ROLE_READ_AUTHENTICATION_POLICY.permission());
        verify(guard)
                .hasGlobal(actor, PlatformOperation.ROLE_UPDATE_AUTHENTICATION_POLICY.permission());
    }
}
