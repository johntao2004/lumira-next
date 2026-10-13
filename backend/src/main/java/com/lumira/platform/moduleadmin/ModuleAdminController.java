package com.lumira.platform.moduleadmin;

import com.lumira.platform.common.BoundedBody;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@com.lumira.platform.modules.ModuleEndpoint("platform")
@RestController
@RequestMapping("/api/platform/modules")
public class ModuleAdminController {
    private final ModuleAdminService service;
    private final com.lumira.platform.plugins.PluginRuntime plugins;
    private final com.lumira.platform.modules.ModuleRegistry registry;

    public ModuleAdminController(
            ModuleAdminService service,
            com.lumira.platform.plugins.PluginRuntime plugins,
            com.lumira.platform.modules.ModuleRegistry registry) {
        this.service = service;
        this.plugins = plugins;
        this.registry = registry;
    }

    @GetMapping
    public List<ModuleAdminService.View> list(Authentication auth) {
        return service.list(auth == null ? null : auth.getName());
    }

    @PutMapping("/{id}/state")
    public ModuleAdminService.State change(
            @PathVariable String id,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "invalidModuleChange")
                    ModuleAdminService.Change input,
            Authentication auth) {
        if (registry.isPlugin(id)) {
            if (input == null || input.enabled() == null || input.version() == null)
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "invalidModuleChange");
            var state =
                    plugins.change(
                            auth.getName(), id, input.enabled(), input.version(), input.reason());
            return new ModuleAdminService.State(state.moduleId(), state.enabled(), state.version());
        }
        return service.change(auth.getName(), id, input);
    }
}
