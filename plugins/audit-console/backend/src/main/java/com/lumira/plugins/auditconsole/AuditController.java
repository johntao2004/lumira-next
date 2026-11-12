package com.lumira.plugins.auditconsole;

import com.lumira.platform.common.*;
import com.lumira.plugins.auditconsole.api.AuditEventResponse;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@com.lumira.platform.modules.ModuleEndpoint("audit-console")
@RestController
@RequestMapping("/api/platform/audit")
public class AuditController {
    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<AuditEventResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            @RequestParam Map<String, String> parameters,
            Authentication auth) {
        return service.list(
                        PageRequest.fromQueryParameters(page, pageSize, search, parameters),
                        auth.getName())
                .map(AuditEventResponse::from);
    }
}
