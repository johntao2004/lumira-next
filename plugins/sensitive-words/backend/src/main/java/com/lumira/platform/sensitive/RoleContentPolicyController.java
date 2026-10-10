package com.lumira.platform.sensitive;

import com.lumira.platform.common.*;
import com.lumira.platform.modules.ModuleEndpoint;
import com.lumira.platform.sensitive.api.ContentPolicyHitResponse;
import com.lumira.platform.sensitive.api.RoleContentPolicyResponse;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@ModuleEndpoint("sensitive-words")
@ContentPolicyExempt
@RequestMapping("/api/platform/sensitive-policies")
public class RoleContentPolicyController {
    private final RoleContentPolicyService service;

    public RoleContentPolicyController(RoleContentPolicyService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    public PageResult<RoleContentPolicyResponse> roles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            Authentication auth) {
        return service.list(new PageRequest(page, pageSize, search), auth.getName())
                .map(RoleContentPolicyResponse::from);
    }

    @PutMapping("/roles/{id}")
    public RoleContentPolicyResponse save(
            @PathVariable UUID id,
            @BoundedBody(maxBytes = 16384, errorCode = "invalidSensitiveWord") @RequestBody
                    RoleContentPolicyService.Input input,
            Authentication auth) {
        return RoleContentPolicyResponse.from(service.save(id, input, auth.getName()));
    }

    @GetMapping("/hits")
    public PageResult<ContentPolicyHitResponse> hits(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            Authentication auth) {
        return service.hits(new PageRequest(page, pageSize, search), auth.getName())
                .map(ContentPolicyHitResponse::from);
    }

    public record Preview(UUID roleId, String text) {}

    @PostMapping("/preview")
    public SensitiveWordService.CheckResult preview(
            @BoundedBody(maxBytes = 810000, errorCode = "sensitiveTextTooLong") @RequestBody
                    Preview input,
            Authentication auth) {
        if (input == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidSensitiveWord");
        return service.preview(input.roleId(), input.text(), auth.getName());
    }
}
