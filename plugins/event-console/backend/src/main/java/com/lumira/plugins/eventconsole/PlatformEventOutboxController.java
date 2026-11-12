package com.lumira.plugins.eventconsole;

import com.lumira.platform.common.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("event-console")
@RequestMapping("/api/platform/event-outbox")
public class PlatformEventOutboxController {
    private final PlatformEventOutboxQuery query;
    private final PlatformEventTraceQuery traceQuery;
    private final PlatformEventOutboxAdminService admin;

    public PlatformEventOutboxController(
            PlatformEventOutboxQuery query,
            PlatformEventTraceQuery traceQuery,
            PlatformEventOutboxAdminService admin) {
        this.query = query;
        this.traceQuery = traceQuery;
        this.admin = admin;
    }

    @GetMapping
    public PageResult<PlatformEventOutboxQuery.Entry> list(
            Authentication actor,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String status,
            @RequestParam java.util.Map<String, String> parameters) {
        PageRequest request = PageRequest.fromQueryParameters(page, pageSize, search, parameters);
        return query.list(
                actor.getName(), request, request.filters().getOrDefault("status", status));
    }

    @GetMapping("/{eventId}/trace")
    public PlatformEventTraceQuery.EventTrace traceEvent(
            @PathVariable java.util.UUID eventId,
            Authentication actor,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return traceQuery.event(actor.getName(), eventId, page, pageSize);
    }

    @GetMapping("/operations/{auditId}/trace")
    public PlatformEventTraceQuery.OperationTrace traceOperation(
            @PathVariable java.util.UUID auditId,
            Authentication actor,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return traceQuery.operation(actor.getName(), auditId, page, pageSize);
    }

    public record ReplayRequest(String reason) {}

    @PostMapping("/{id}/replay")
    public EventRecoveryPort.Replay replay(
            @PathVariable java.util.UUID id,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "invalidEventReplay")
                    ReplayRequest input,
            Authentication actor) {
        return admin.replay(id, actor.getName(), input == null ? null : input.reason());
    }
}
