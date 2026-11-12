package com.lumira.plugins.navigationconsole;

import com.lumira.platform.modules.ModuleEndpoint;
import com.lumira.platform.navigation.api.NavigationMenuResponse;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform/navigation")
@ModuleEndpoint("navigation-console")
public class NavigationAdminController {
    private final NavigationMenuService service;

    public NavigationAdminController(NavigationMenuService service) {
        this.service = service;
    }

    @GetMapping("/menus")
    public List<NavigationMenuResponse> list(Authentication auth) {
        return service.adminList(auth.getName()).stream()
                .map(NavigationMenuResponse::from)
                .toList();
    }

    @GetMapping("/menus/{id}")
    public NavigationMenuResponse find(@PathVariable UUID id, Authentication auth) {
        return NavigationMenuResponse.from(service.find(id, auth.getName()));
    }

    @PostMapping("/menus")
    @ResponseStatus(HttpStatus.CREATED)
    public NavigationMenuResponse create(
            @RequestBody NavigationMenuService.Input input, Authentication auth) {
        return NavigationMenuResponse.from(service.create(input, auth.getName()));
    }

    @PutMapping("/menus/{id}")
    public NavigationMenuResponse update(
            @PathVariable UUID id,
            @RequestBody NavigationMenuService.Input input,
            Authentication auth) {
        return NavigationMenuResponse.from(service.update(id, input, auth.getName()));
    }

    @PatchMapping("/menus/{id}/status")
    public NavigationMenuResponse status(
            @PathVariable UUID id, @RequestBody StatusInput input, Authentication auth) {
        return NavigationMenuResponse.from(
                service.status(id, input.version(), input.enabled(), auth.getName()));
    }

    @PutMapping("/menus/reorder")
    public List<NavigationMenuResponse> reorder(
            @RequestBody List<NavigationMenuService.ReorderItem> input, Authentication auth) {
        return service.reorder(input, auth.getName()).stream()
                .map(NavigationMenuResponse::from)
                .toList();
    }

    @DeleteMapping("/menus/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam long version, Authentication auth) {
        service.delete(id, version, auth.getName());
    }

    public record StatusInput(long version, boolean enabled) {}
}
