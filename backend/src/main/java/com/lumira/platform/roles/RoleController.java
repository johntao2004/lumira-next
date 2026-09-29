package com.lumira.platform.roles;

import com.lumira.platform.common.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("directory")
@RequestMapping("/api/platform")
public class RoleController {
    private final RoleService service;
    public RoleController(RoleService service) { this.service=service; }
    @GetMapping("/roles")
    public PageResult<RoleRepository.Role> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String search,@RequestParam Map<String,String> parameters) { return service.list(PageRequest.fromQueryParameters(page,pageSize,search,parameters)); }
    @GetMapping("/roles/{id}") public RoleRepository.Role find(@PathVariable UUID id) { return service.find(id); }
    @GetMapping("/permissions") public List<String> permissions() { return RoleService.PERMISSIONS.stream().sorted().toList(); }
    @PostMapping("/roles") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public RoleRepository.Role create(@RequestBody RoleService.RoleInput input,Authentication auth) { return service.create(input,auth.getName()); }
    @PutMapping("/roles/{id}") public RoleRepository.Role update(@PathVariable UUID id,@RequestBody RoleService.RoleInput input,Authentication auth) { return service.update(id,input,auth.getName()); }
    @DeleteMapping("/roles/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id,@RequestParam long version,Authentication auth) { service.delete(id,version,auth.getName()); }
    @GetMapping("/users/{id}/roles") public RoleService.UserRoles userRoles(@PathVariable UUID id,Authentication auth) { return service.userRoles(id,auth.getName()); }
    @PutMapping("/users/{id}/roles") public RoleService.UserRoles assign(@PathVariable UUID id,@RequestBody RoleService.Assignment input,Authentication auth) { return service.assign(id,input,auth.getName()); }
}
