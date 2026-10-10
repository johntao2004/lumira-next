package com.lumira.platform.organizations;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("directory")
@RequestMapping("/api/platform/users/{id}/organizations")
public class MembershipController {
    private final OrganizationService service;

    public MembershipController(OrganizationService service) {
        this.service = service;
    }

    @GetMapping
    public OrganizationService.Membership memberships(@PathVariable UUID id, Authentication auth) {
        return service.memberships(id, auth.getName());
    }

    @PutMapping
    public OrganizationService.Membership assign(
            @PathVariable UUID id,
            @RequestBody OrganizationService.MembershipInput input,
            Authentication auth) {
        return service.assign(id, input, auth.getName());
    }
}
