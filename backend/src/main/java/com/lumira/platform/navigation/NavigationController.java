package com.lumira.platform.navigation;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.lumira.platform.modules.ModuleEndpoint;

@RestController
@RequestMapping("/api/platform/navigation")
@ModuleEndpoint("navigation")
public class NavigationController {
    private final NavigationMenuService service;

    public NavigationController(NavigationMenuService service) {
        this.service = service;
    }

    /** Permission-filtered tree consumed by both shell layouts. */
    @GetMapping
    public List<NavigationMenuService.RuntimeNode> runtime(Authentication auth) {
        return service.runtime(auth.getName());
    }

    @GetMapping("/menus")
    public List<NavigationMenuRepository.Menu> list(Authentication auth) {
        return service.adminList(auth.getName());
    }

    @GetMapping("/menus/{id}")
    public NavigationMenuRepository.Menu find(@PathVariable UUID id, Authentication auth) {
        return service.find(id, auth.getName());
    }

    @PostMapping("/menus")
    @ResponseStatus(HttpStatus.CREATED)
    public NavigationMenuRepository.Menu create(@RequestBody NavigationMenuService.Input input, Authentication auth) {
        return service.create(input, auth.getName());
    }

    @PutMapping("/menus/{id}")
    public NavigationMenuRepository.Menu update(@PathVariable UUID id, @RequestBody NavigationMenuService.Input input, Authentication auth) {
        return service.update(id, input, auth.getName());
    }

    @PatchMapping("/menus/{id}/status")
    public NavigationMenuRepository.Menu status(@PathVariable UUID id, @RequestBody StatusInput input, Authentication auth) {
        return service.status(id, input.version(), input.enabled(), auth.getName());
    }

    @PutMapping("/menus/reorder")
    public List<NavigationMenuRepository.Menu> reorder(@RequestBody List<NavigationMenuService.ReorderItem> input, Authentication auth) {
        return service.reorder(input, auth.getName());
    }

    @DeleteMapping("/menus/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam long version, Authentication auth) {
        service.delete(id, version, auth.getName());
    }

    public record StatusInput(long version, boolean enabled) {}
}
