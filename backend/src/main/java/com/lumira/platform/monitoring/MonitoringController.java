package com.lumira.platform.monitoring;

import com.lumira.platform.modules.ModuleEndpoint;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ModuleEndpoint("monitoring")
@RequestMapping("/api/platform/monitoring")
public class MonitoringController {
    private final MonitoringService service;

    public MonitoringController(MonitoringService service) { this.service = service; }

    @GetMapping("/summary")
    public MonitoringService.Snapshot summary(Authentication actor) {
        return service.summary(actor.getName());
    }
}
