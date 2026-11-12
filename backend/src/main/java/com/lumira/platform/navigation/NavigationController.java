package com.lumira.platform.navigation;

import com.lumira.platform.modules.ModuleEndpoint;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@ModuleEndpoint("navigation")
@RequestMapping("/api/platform/navigation")
public class NavigationController {
    private final NavigationRuntimeService service;

    public NavigationController(NavigationRuntimeService service) {
        this.service = service;
    }

    @GetMapping
    public List<NavigationTree.RuntimeNode> runtime(Authentication actor) {
        return service.runtime(actor.getName());
    }
}
