package com.lumira.platform.settings;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
@RequestMapping("/api/platform/settings/registration")
public class RegistrationSettingsController {
    private final RegistrationSettingsService service;

    public RegistrationSettingsController(RegistrationSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public RegistrationSettingsService.View read(Authentication auth) {
        return service.read(auth.getName());
    }

    @GetMapping("/roles")
    public com.lumira.platform.common.PageResult<
                    com.lumira.platform.common.RegistrationRolePort.Candidate>
            roles(
                    @RequestParam(defaultValue = "1") int page,
                    @RequestParam(defaultValue = "20") int pageSize,
                    @RequestParam(defaultValue = "") String search,
                    Authentication auth) {
        return service.roles(auth.getName(), new PageRequest(page, pageSize, search));
    }

    @PutMapping
    public RegistrationSettingsService.View write(
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "invalidRegistrationPolicy")
                    RegistrationSettingsService.Input input,
            Authentication auth) {
        return service.save(input, auth.getName());
    }
}
