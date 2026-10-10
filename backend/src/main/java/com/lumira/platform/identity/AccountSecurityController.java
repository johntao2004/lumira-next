package com.lumira.platform.identity;

import com.lumira.platform.common.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("identity")
@RequestMapping("/api/account/security-events")
public class AccountSecurityController {
    private final AccountSecurityEvents events;

    public AccountSecurityController(AccountSecurityEvents events) {
        this.events = events;
    }

    @GetMapping
    public PageResult<AccountSecurityEvents.Event> list(
            Authentication actor,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam java.util.Map<String, String> parameters) {
        return events.list(
                actor.getName(), PageRequest.fromQueryParameters(page, pageSize, "", parameters));
    }
}
