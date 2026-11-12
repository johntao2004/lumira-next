package com.lumira.plugins.directory.roles;

import com.lumira.platform.common.BoundedBody;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("directory")
@RequestMapping("/api/platform/roles/{id}/authentication-policy")
public class RoleAuthenticationPolicyController {
    private final RoleAuthenticationPolicyService service;

    public RoleAuthenticationPolicyController(RoleAuthenticationPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public RoleAuthenticationPolicyService.View read(@PathVariable UUID id, Authentication actor) {
        return service.read(id, actor.getName());
    }

    @PutMapping
    public RoleAuthenticationPolicyService.View write(
            @PathVariable UUID id,
            @RequestBody @BoundedBody(maxBytes = 16_384, errorCode = "invalidAuthenticationPolicy")
                    RoleAuthenticationPolicyService.Input input,
            Authentication actor) {
        return service.save(id, input, actor.getName());
    }
}
