package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AuthenticationCapabilityContributor;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.GlobalAuthenticationPolicyPort;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.common.RoleAuthenticationPolicyPort;
import com.lumira.platform.identity.api.CaptchaConfigurationPort;
import com.lumira.platform.identity.api.CaptchaConfigurationPort.Mode;
import com.lumira.platform.identity.api.PasskeyPolicyPort;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class AuthenticationPolicyCaptchaContractTest {
    @Test
    void publicMethodsReportsAlwaysActionsSeparatelyFromRiskActions() throws Exception {
        var data =
                new CaptchaConfigurationPort.Data(
                        true,
                        "LOCAL",
                        "IMAGE_TEXT",
                        actions(
                                Map.of(
                                        CaptchaProofPort.Action.PASSWORD_LOGIN, Mode.ALWAYS,
                                        CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST,
                                                Mode.RISK)));
        var settings = mock(CaptchaConfigurationPort.class);
        when(settings.current())
                .thenReturn(new CaptchaConfigurationPort.Snapshot(4, UUID.randomUUID(), data));
        when(settings.readyFor(any())).thenReturn(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<RoleAuthenticationPolicyPort> rolePolicies = mock(ObjectProvider.class);
        var captcha =
                new CaptchaProofService(
                        null,
                        settings,
                        null,
                        null,
                        null,
                        null,
                        rolePolicies,
                        mock(PlatformTransactionManager.class));
        var global = mock(GlobalAuthenticationPolicyPort.class);
        when(global.current())
                .thenReturn(
                        new GlobalAuthenticationPolicyPort.Policy(
                                1, UUID.randomUUID(), Set.of(PrimaryMethod.PASSWORD), false));
        var passkeys = mock(PasskeyPolicyPort.class);
        when(passkeys.currentPolicy())
                .thenReturn(
                        new PasskeyPolicyPort.Policy(
                                false, false, false, "localhost", "Lumira", List.of(), 120));
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthenticationCapabilityContributor> contributors =
                mock(ObjectProvider.class);
        when(contributors.orderedStream()).thenReturn(Stream.of(captcha));

        var methods =
                new AuthenticationPolicyService(
                                null,
                                global,
                                mock(RoleAuthenticationPolicyPort.class),
                                passkeys,
                                mock(PluginExtensionsPort.class),
                                contributors)
                        .publicMethods();

        assertTrue(methods.captcha().required());
        assertEquals(
                List.of("PASSWORD_LOGIN", "PASSWORD_RECOVERY_REQUEST"),
                methods.captcha().actions());
        assertEquals(List.of("PASSWORD_LOGIN"), methods.captcha().alwaysActions());
        String json = new ObjectMapper().writeValueAsString(methods);
        assertTrue(json.contains("\"alwaysActions\":[\"PASSWORD_LOGIN\"]"));
    }

    @Test
    void riskOnlyActionIsNotPublishedAsAlwaysRequired() throws Exception {
        var data =
                new CaptchaConfigurationPort.Data(
                        true,
                        "LOCAL",
                        "IMAGE_TEXT",
                        actions(Map.of(CaptchaProofPort.Action.PASSWORD_LOGIN, Mode.RISK)));
        var settings = mock(CaptchaConfigurationPort.class);
        when(settings.current())
                .thenReturn(new CaptchaConfigurationPort.Snapshot(2, UUID.randomUUID(), data));
        when(settings.readyFor(any())).thenReturn(true);
        var captcha =
                new CaptchaProofService(
                        null,
                        settings,
                        null,
                        null,
                        null,
                        null,
                        null,
                        mock(PlatformTransactionManager.class));

        var policy = captcha.authenticationCapabilities().captchaPolicy();

        assertFalse(policy.required());
        assertEquals(List.of("PASSWORD_LOGIN"), policy.actions());
        assertTrue(policy.alwaysActions().isEmpty());
    }

    private static Map<String, Mode> actions(Map<CaptchaProofPort.Action, Mode> configuredActions) {
        var result = new EnumMap<CaptchaProofPort.Action, Mode>(CaptchaProofPort.Action.class);
        for (var action : CaptchaProofPort.Action.values()) result.put(action, Mode.OFF);
        result.putAll(configuredActions);
        var names = new java.util.LinkedHashMap<String, Mode>();
        result.forEach((action, mode) -> names.put(action.name(), mode));
        return names;
    }
}
