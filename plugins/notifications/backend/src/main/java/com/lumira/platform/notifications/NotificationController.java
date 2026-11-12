package com.lumira.platform.notifications;

import com.lumira.platform.common.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("notifications")
@RequestMapping("/api/account/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<NotificationStore.Entry> list(
            Authentication actor,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(required = false) Boolean read) {
        return service.list(actor.getName(), new PageRequest(page, pageSize, ""), unread, read);
    }

    @GetMapping("/unread-count")
    public long unreadCount(Authentication actor) {
        return service.unreadCount(actor.getName());
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(Authentication actor, @PathVariable UUID id) {
        service.markRead(actor.getName(), id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(Authentication actor) {
        service.markAllRead(actor.getName());
    }
}
