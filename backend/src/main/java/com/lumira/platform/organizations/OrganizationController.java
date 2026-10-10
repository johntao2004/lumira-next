package com.lumira.platform.organizations;

import com.lumira.platform.common.*;
import com.lumira.platform.organizations.api.OrganizationResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("directory")
@RequestMapping("/api/platform/organizations")
public class OrganizationController {
    private final OrganizationService service;

    public OrganizationController(OrganizationService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<OrganizationResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) String parent,
            @RequestParam Map<String, String> parameters,
            Authentication auth) {
        return service.list(
                        PageRequest.fromQueryParameters(page, pageSize, search, parameters),
                        parent,
                        auth.getName())
                .map(OrganizationResponse::from);
    }

    @GetMapping("/{id}/ancestors")
    public java.util.List<OrganizationResponse> ancestors(
            @PathVariable UUID id, Authentication auth) {
        return service.ancestors(id, auth.getName()).stream()
                .map(OrganizationResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public OrganizationResponse find(@PathVariable UUID id, Authentication auth) {
        return OrganizationResponse.from(service.find(id, auth.getName()));
    }

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public OrganizationResponse create(
            @RequestBody OrganizationService.Input input, Authentication auth) {
        return OrganizationResponse.from(service.create(input, auth.getName()));
    }

    @PutMapping("/{id}")
    public OrganizationResponse update(
            @PathVariable UUID id,
            @RequestBody OrganizationService.Input input,
            Authentication auth) {
        return OrganizationResponse.from(service.update(id, input, auth.getName()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam long version, Authentication auth) {
        service.delete(id, version, auth.getName());
    }
}
