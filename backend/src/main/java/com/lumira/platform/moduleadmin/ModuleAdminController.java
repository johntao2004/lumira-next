package com.lumira.platform.moduleadmin;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModulePackageService;
import java.util.UUID;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@com.lumira.platform.modules.ModuleEndpoint("platform")
@RestController
@RequestMapping("/api/platform/modules")
public class ModuleAdminController {
 private final ModuleAdminService service;
 private final ModulePackageService packages;
 public ModuleAdminController(ModuleAdminService service, ModulePackageService packages){this.service=service;this.packages=packages;}
 @GetMapping public List<ModuleAdminService.View> list(){return service.list();}
 @GetMapping("/{id}/history") public com.lumira.platform.common.PageResult<ModuleAdminService.Revision> history(@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam java.util.Map<String,String> parameters,Authentication auth){return service.history(auth.getName(),id,com.lumira.platform.common.PageRequest.fromQueryParameters(page,pageSize,"",parameters));}
 @PutMapping("/{id}/state") public ModuleAdminService.State change(@PathVariable String id,@RequestBody @BoundedBody(maxBytes=4096,errorCode="invalidModuleChange") ModuleAdminService.Change input,Authentication auth){return service.change(auth.getName(),id,input);}
 @GetMapping("/packages") public List<ModulePackageService.View> packages(Authentication auth){return packages.list(auth.getName(),"");}
 @GetMapping("/{id}/packages") public List<ModulePackageService.View> modulePackages(@PathVariable String id,Authentication auth){return packages.list(auth.getName(),id);}
 @PostMapping("/packages") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
 public ModulePackageService.View stage(@RequestBody @BoundedBody(maxBytes=65536,errorCode="invalidModulePackage") ModulePackageService.StageRequest input,Authentication auth){return packages.stage(auth.getName(),input);}
 @PostMapping("/packages/{id}/activate")
 public ModulePackageService.View activate(@PathVariable UUID id,@RequestBody @BoundedBody(maxBytes=4096,errorCode="invalidModulePackage") ModulePackageService.LifecycleRequest input,Authentication auth){return packages.activate(auth.getName(),id,input);}
 @PostMapping("/packages/{id}/reject")
 public ModulePackageService.View reject(@PathVariable UUID id,@RequestBody @BoundedBody(maxBytes=4096,errorCode="invalidModulePackage") ModulePackageService.LifecycleRequest input,Authentication auth){return packages.reject(auth.getName(),id,input);}
}
