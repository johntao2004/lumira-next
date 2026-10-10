package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lumira.platform.common.CaptchaProofPort;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CaptchaSettingsServiceTest {
    @Test
    void localProviderMayBeEnabledWhileEveryGlobalActionIsOff() {
        var actions =
                new EnumMap<CaptchaProofPort.Action, CaptchaSettingsService.Mode>(
                        CaptchaProofPort.Action.class);
        for (var action : CaptchaProofPort.Action.values()) {
            actions.put(action, CaptchaSettingsService.Mode.OFF);
        }

        assertDoesNotThrow(() -> data(true, actions).validate());
        assertDoesNotThrow(() -> data(false, actions).validate());
    }

    @Test
    void localProviderCannotBeDisabledWhileAnyGlobalActionRequiresCaptcha() {
        var actions =
                new EnumMap<CaptchaProofPort.Action, CaptchaSettingsService.Mode>(
                        CaptchaProofPort.Action.class);
        for (var action : CaptchaProofPort.Action.values()) {
            actions.put(action, CaptchaSettingsService.Mode.OFF);
        }
        actions.put(CaptchaProofPort.Action.PASSWORD_LOGIN, CaptchaSettingsService.Mode.ALWAYS);

        assertThrows(ResponseStatusException.class, () -> data(false, actions).validate());
        assertDoesNotThrow(() -> data(true, actions).validate());
    }

    @Test
    void providerAndChallengeTypeAreFixedToImplementedLocalModes() {
        var actions =
                new EnumMap<CaptchaProofPort.Action, CaptchaSettingsService.Mode>(
                        CaptchaProofPort.Action.class);
        for (var action : CaptchaProofPort.Action.values())
            actions.put(action, CaptchaSettingsService.Mode.OFF);
        assertThrows(
                ResponseStatusException.class,
                () -> data(true, actions, "TENCENT", "IMAGE_TEXT").validate());
        assertThrows(
                ResponseStatusException.class,
                () -> data(true, actions, "LOCAL", "AUDIO").validate());
        assertDoesNotThrow(() -> data(true, actions, "LOCAL", "SLIDER").validate());
    }

    private static CaptchaSettingsService.Data data(
            boolean enabled, Map<CaptchaProofPort.Action, CaptchaSettingsService.Mode> actions) {
        var names = new java.util.LinkedHashMap<String, CaptchaSettingsService.Mode>();
        actions.forEach((action, mode) -> names.put(action.name(), mode));
        return new CaptchaSettingsService.Data(enabled, "LOCAL", "IMAGE_TEXT", names);
    }

    private static CaptchaSettingsService.Data data(
            boolean enabled,
            Map<CaptchaProofPort.Action, CaptchaSettingsService.Mode> actions,
            String provider,
            String type) {
        var names = new java.util.LinkedHashMap<String, CaptchaSettingsService.Mode>();
        actions.forEach((action, mode) -> names.put(action.name(), mode));
        return new CaptchaSettingsService.Data(enabled, provider, type, names);
    }
}
