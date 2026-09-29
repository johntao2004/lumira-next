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
@RequestMapping("/api/platform/settings/security")
public class SecuritySettingsController {
    public record Input(long version, SecuritySettings data, String changeReason) {}

    private final SecuritySettingsService service;

    public SecuritySettingsController(SecuritySettingsService service) {
        this.service = service;
    }

    @GetMapping
    public SecuritySettingsService.View read() {
        return service.read();
    }

    @PutMapping
    public SecuritySettingsService.View write(
            @BoundedBody(maxBytes = 16_384, errorCode = "invalidSecurity") @RequestBody Input input,
            Authentication actor) {
        if (input == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "invalidSecurity");
        return service.save(input.data(), actor.getName(), input.version(), input.changeReason());
    }
}
