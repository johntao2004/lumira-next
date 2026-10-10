package com.lumira.platform.settings;

import com.lumira.platform.common.BoundedBody;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
@RequestMapping("/api/platform/settings/passkey")
public class PasskeySettingsController {
    public record Input(long version, PasskeySettings data, String changeReason) {}

    private final PasskeySettingsService service;

    public PasskeySettingsController(PasskeySettingsService service) {
        this.service = service;
    }

    @GetMapping
    public PasskeySettingsService.View read() {
        return service.read();
    }

    @PutMapping
    public PasskeySettingsService.View write(
            @BoundedBody(maxBytes = 16_384, errorCode = "invalidPasskeySettings") @RequestBody
                    Input input,
            Authentication actor) {
        if (input == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidPasskeySettings");
        return service.save(input.data(), actor.getName(), input.version(), input.changeReason());
    }
}
