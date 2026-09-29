package com.lumira.platform.notifications;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("notifications")
@RequestMapping("/api/platform/notifications")
public class NotificationAdminController {
    private final NotificationAdminService service;

    public NotificationAdminController(NotificationAdminService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<NotificationStore.AdminEntry> list(Authentication actor,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "10") int pageSize,
                                                          @RequestParam(defaultValue = "") String search,
                                                          @RequestParam java.util.Map<String,String> parameters) {
        return service.list(actor.getName(), PageRequest.fromQueryParameters(page, pageSize, search, parameters));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NotificationAdminService.Published publish(Authentication actor,
                                                        @RequestBody NotificationAdminService.Publish input) {
        return service.publish(actor.getName(), input);
    }
}
