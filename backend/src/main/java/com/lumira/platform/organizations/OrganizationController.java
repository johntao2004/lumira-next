package com.lumira.platform.organizations;

import com.lumira.platform.common.*;
import java.util.UUID;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("directory")
@RequestMapping("/api/platform/organizations")
public class OrganizationController {
    private final OrganizationService service;
    public OrganizationController(OrganizationService service) { this.service=service; }
    @GetMapping public PageResult<OrganizationRepository.Organization> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String search,@RequestParam(required=false) String parent,@RequestParam Map<String,String> parameters,Authentication auth) { return service.list(PageRequest.fromQueryParameters(page,pageSize,search,parameters),parent,auth.getName()); }
    @GetMapping("/{id}/ancestors") public java.util.List<OrganizationRepository.Organization> ancestors(@PathVariable UUID id,Authentication auth) { return service.ancestors(id,auth.getName()); }
    @GetMapping("/{id}") public OrganizationRepository.Organization find(@PathVariable UUID id,Authentication auth) { return service.find(id,auth.getName()); }
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public OrganizationRepository.Organization create(@RequestBody OrganizationService.Input input,Authentication auth) { return service.create(input,auth.getName()); }
    @PutMapping("/{id}") public OrganizationRepository.Organization update(@PathVariable UUID id,@RequestBody OrganizationService.Input input,Authentication auth) { return service.update(id,input,auth.getName()); }
    @DeleteMapping("/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id,@RequestParam long version,Authentication auth) { service.delete(id,version,auth.getName()); }
}
