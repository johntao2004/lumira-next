package com.lumira.platform.settings;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
public class MaintenanceController {
    public record Input(long version, MaintenanceSettings data, String changeReason) {}

    public record PublicStatus(boolean active, String title, String message, Instant endAt) {}

    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    @GetMapping("/api/platform/settings/maintenance")
    public MaintenanceService.View read() {
        return service.read();
    }

    @GetMapping("/api/platform/settings/maintenance/roles")
    public PageResult<MaintenanceService.RoleOption> roles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search) {
        return service.roles(new PageRequest(page, pageSize, search));
    }

    @GetMapping("/api/public/maintenance")
    public PublicStatus status() {
        var data = service.read().data();
        return new PublicStatus(
                data.activeAt(Instant.now()), data.title(), data.message(), data.endAt());
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
