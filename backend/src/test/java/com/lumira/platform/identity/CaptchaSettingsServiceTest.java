package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.identity.api.CaptchaConfigurationPort;
import com.lumira.plugins.authenticationconsole.CaptchaSettingsService;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CaptchaSettingsServiceTest {
    @Test
    void administrationRetainsTheExistingHttpErrorForInvalidDomainConfiguration() {
        var service =
                new CaptchaSettingsService(
                        org.mockito.Mockito.mock(
                                org.springframework.jdbc.core.simple.JdbcClient.class),
                        org.mockito.Mockito.mock(CaptchaConfigurationPort.class),
                        org.mockito.Mockito.mock(
                                com.lumira.platform.common.AuthenticationPolicyWriteLock.class),
                        org.mockito.Mockito.mock(
                                com.lumira.platform.common.AdministrationPort.class),
                        org.mockito.Mockito.mock(com.lumira.platform.common.AuditPort.class),
                        new tools.jackson.databind.json.JsonMapper());
        var failure =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.save(
                                        "operator",
                                        new CaptchaSettingsService.Input(
                                                1,
                                                new CaptchaConfigurationPort.Data(
                                                        true, "TENCENT", "IMAGE_TEXT", Map.of()),
                                                "QA invalid provider")));
        org.junit.jupiter.api.Assertions.assertEquals(
                org.springframework.http.HttpStatus.BAD_REQUEST, failure.getStatusCode());
        org.junit.jupiter.api.Assertions.assertEquals(
                "invalidCaptchaSettings", failure.getReason());
    }

    @Test
    void localProviderMayBeEnabledWhileEveryGlobalActionIsOff() {
        var actions =
                new EnumMap<CaptchaProofPort.Action, CaptchaConfigurationPort.Mode>(
                        CaptchaProofPort.Action.class);
        for (var action : CaptchaProofPort.Action.values()) {
            actions.put(action, CaptchaConfigurationPort.Mode.OFF);
        }

        assertDoesNotThrow(() -> data(true, actions).validate());
        assertDoesNotThrow(() -> data(false, actions).validate());
    }

    @Test
    void localProviderCannotBeDisabledWhileAnyGlobalActionRequiresCaptcha() {
        var actions =
                new EnumMap<CaptchaProofPort.Action, CaptchaConfigurationPort.Mode>(
                        CaptchaProofPort.Action.class);
        for (var action : CaptchaProofPort.Action.values()) {
            actions.put(action, CaptchaConfigurationPort.Mode.OFF);
        }
        actions.put(CaptchaProofPort.Action.PASSWORD_LOGIN, CaptchaConfigurationPort.Mode.ALWAYS);

        assertThrows(CaptchaConfigurationPort.Invalid.class, () -> data(false, actions).validate());
        assertDoesNotThrow(() -> data(true, actions).validate());
    }

    @Test
    void providerAndChallengeTypeAreFixedToImplementedLocalModes() {
        var actions =
                new EnumMap<CaptchaProofPort.Action, CaptchaConfigurationPort.Mode>(
                        CaptchaProofPort.Action.class);
        for (var action : CaptchaProofPort.Action.values())
            actions.put(action, CaptchaConfigurationPort.Mode.OFF);
        assertThrows(
                CaptchaConfigurationPort.Invalid.class,
                () -> data(true, actions, "TENCENT", "IMAGE_TEXT").validate());
        assertThrows(
                CaptchaConfigurationPort.Invalid.class,
                () -> data(true, actions, "LOCAL", "AUDIO").validate());
        assertDoesNotThrow(() -> data(true, actions, "LOCAL", "SLIDER").validate());
    }

    private static CaptchaConfigurationPort.Data data(
            boolean enabled, Map<CaptchaProofPort.Action, CaptchaConfigurationPort.Mode> actions) {
        var names = new java.util.LinkedHashMap<String, CaptchaConfigurationPort.Mode>();
        actions.forEach((action, mode) -> names.put(action.name(), mode));
        return new CaptchaConfigurationPort.Data(enabled, "LOCAL", "IMAGE_TEXT", names);
    }

    private static CaptchaConfigurationPort.Data data(
            boolean enabled,
            Map<CaptchaProofPort.Action, CaptchaConfigurationPort.Mode> actions,
            String provider,
            String type) {
        var names = new java.util.LinkedHashMap<String, CaptchaConfigurationPort.Mode>();
        actions.forEach((action, mode) -> names.put(action.name(), mode));
        return new CaptchaConfigurationPort.Data(enabled, provider, type, names);
    }
}
