package com.lumira.platform.roles;

import com.lumira.platform.common.*;
import com.lumira.platform.roles.api.RoleResponse;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("directory")
@RequestMapping("/api/platform")
public class RoleController {
    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    public PageResult<RoleResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            @RequestParam Map<String, String> parameters,
            Authentication auth) {
        return service.list(
                        PageRequest.fromQueryParameters(page, pageSize, search, parameters),
                        auth.getName())
                .map(RoleResponse::from);
    }

    @GetMapping("/roles/{id}")
    public RoleResponse find(@PathVariable UUID id, Authentication auth) {
        return RoleResponse.from(service.find(id, auth.getName()));
    }

    @GetMapping("/permissions")
    public List<String> permissions(Authentication auth) {
        return service.permissionCodes(auth.getName());
    }

    @GetMapping("/permission-catalog")
    public List<RoleService.PermissionCatalogGroup> permissionCatalog(Authentication auth) {
        return service.permissionCatalog(auth.getName());
    }

    @PostMapping("/roles")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public RoleResponse create(@RequestBody RoleService.RoleInput input, Authentication auth) {
        return RoleResponse.from(service.create(input, auth.getName()));
    }

    @PutMapping("/roles/{id}")
    public RoleResponse update(
            @PathVariable UUID id, @RequestBody RoleService.RoleInput input, Authentication auth) {
        return RoleResponse.from(service.update(id, input, auth.getName()));
    }

    @DeleteMapping("/roles/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam long version, Authentication auth) {
        service.delete(id, version, auth.getName());
    }

    @GetMapping("/users/{id}/roles")
    public RoleService.UserRoles userRoles(@PathVariable UUID id, Authentication auth) {
        return service.userRoles(id, auth.getName());
    }

    @GetMapping("/users/{id}/role-options")
    public PageResult<RoleService.AssignmentRoleOption> roleOptions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            @RequestParam Map<String, String> parameters,
            Authentication auth) {
        return service.assignmentOptions(
                id,
                PageRequest.fromQueryParameters(page, pageSize, search, parameters),
                auth.getName());
    }

    @PutMapping("/users/{id}/roles")
    public RoleService.UserRoles assign(
            @PathVariable UUID id, @RequestBody RoleService.Assignment input, Authentication auth) {
        return service.assign(id, input, auth.getName());
    }
}
