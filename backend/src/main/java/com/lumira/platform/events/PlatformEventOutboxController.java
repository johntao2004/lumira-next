package com.lumira.platform.events;

import com.lumira.platform.common.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("events")
@RequestMapping("/api/platform/event-outbox")
public class PlatformEventOutboxController {
    private final PlatformEventOutboxQuery query;
    private final PlatformEventOutboxAdminService admin;
    public PlatformEventOutboxController(PlatformEventOutboxQuery query, PlatformEventOutboxAdminService admin) { this.query = query; this.admin = admin; }
    @GetMapping
    public PageResult<PlatformEventOutboxQuery.Entry> list(Authentication actor,
        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize,
        @RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "") String status,
        @RequestParam java.util.Map<String,String> parameters) {
        PageRequest request = PageRequest.fromQueryParameters(page, pageSize, search, parameters);
        return query.list(actor.getName(), request, request.filters().getOrDefault("status", status));
    }

    public record ReplayRequest(String reason) {}

    @PostMapping("/{id}/replay")
    public PlatformEventOutboxStore.Replay replay(@PathVariable java.util.UUID id,
        @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "invalidEventReplay") ReplayRequest input,
        Authentication actor) {
        return admin.replay(id, actor.getName(), input == null ? null : input.reason());
    }
}
