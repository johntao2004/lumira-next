package com.lumira.platform.identity;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.OnlineSessionResponse;
import com.lumira.platform.modules.ModuleEndpoint;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@ModuleEndpoint("directory")
@RequestMapping("/api/platform/online-users")
public class OnlineSessionController {
    private final OnlineSessionService service;

    public OnlineSessionController(OnlineSessionService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<OnlineSessionResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            @RequestParam java.util.Map<String, String> parameters,
            Authentication actor) {
        return service.list(
                        actor.getName(),
                        PageRequest.fromQueryParameters(page, pageSize, search, parameters))
                .map(OnlineSessionResponse::from);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id, Authentication actor) {
        service.revoke(actor.getName(), id);
    }

    @DeleteMapping("/user/{id}")
    public Revocation revokeUser(@PathVariable UUID id, Authentication actor) {
        return new Revocation(service.revokeUser(actor.getName(), id));
    }

    public record Revocation(int sessions) {}
}
