package com.lumira.platform.settings;

import com.lumira.platform.common.BoundedBody;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
@RequestMapping("/api/platform/settings/authentication")
public class GlobalAuthenticationPolicyController {
    private final GlobalAuthenticationPolicyService service;

    public GlobalAuthenticationPolicyController(GlobalAuthenticationPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public GlobalAuthenticationPolicyService.View read(Authentication actor) {
        return service.read(actor.getName());
    }

    @PutMapping
    public GlobalAuthenticationPolicyService.View write(
            @RequestBody @BoundedBody(maxBytes = 16_384, errorCode = "invalidAuthenticationPolicy")
                    GlobalAuthenticationPolicyService.Input input,
            Authentication actor) {
        return service.save(input, actor.getName());
    }
}
