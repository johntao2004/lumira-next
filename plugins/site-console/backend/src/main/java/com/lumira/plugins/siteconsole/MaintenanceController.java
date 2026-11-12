package com.lumira.plugins.siteconsole;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.settings.*;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("site-console")
public class MaintenanceController {
    public record Input(long version, MaintenanceSettings data, String changeReason) {}

    public record PublicStatus(boolean active, String title, String message, Instant endAt) {}

    private final MaintenanceService service;
    private final AdministrationPort administration;

    public MaintenanceController(MaintenanceService service, AdministrationPort administration) {
        this.service = service;
        this.administration = administration;
    }

    @GetMapping("/api/platform/settings/maintenance")
    public MaintenanceService.View read(Authentication auth) {
        administration.requirePageGlobal(auth.getName(), PlatformPageId.MAINTENANCE);
        return service.read();
    }

    @GetMapping("/api/platform/settings/maintenance/roles")
    public PageResult<MaintenanceService.RoleOption> roles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            Authentication auth) {
        administration.requirePageGlobal(auth.getName(), PlatformPageId.MAINTENANCE);
        return service.roles(new PageRequest(page, pageSize, search));
    }

    @PutMapping("/api/platform/settings/maintenance")
    public MaintenanceService.View write(
            @BoundedBody(maxBytes = 32768, errorCode = "invalidMaintenance") @RequestBody
                    Input input,
            Authentication auth) {
        if (input == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidMaintenance");
        return service.save(input.data(), auth.getName(), input.version(), input.changeReason());
    }
}
