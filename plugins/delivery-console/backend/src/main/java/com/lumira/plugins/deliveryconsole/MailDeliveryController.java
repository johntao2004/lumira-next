package com.lumira.plugins.deliveryconsole;

import com.lumira.platform.common.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("delivery-console")
@RequestMapping("/api/platform/mail-deliveries")
public class MailDeliveryController {
    private final MailDeliveryQuery query;

    public MailDeliveryController(MailDeliveryQuery query) {
        this.query = query;
    }

    @GetMapping
    public PageResult<MailDeliveryQuery.Entry> list(
            Authentication actor,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String state,
            @RequestParam java.util.Map<String, String> parameters) {
        PageRequest request = PageRequest.fromQueryParameters(page, pageSize, search, parameters);
        return query.list(actor.getName(), request, request.filters().getOrDefault("state", state));
    }
}
