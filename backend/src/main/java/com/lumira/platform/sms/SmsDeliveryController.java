package com.lumira.platform.sms;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("communication")
@RequestMapping("/api/platform/sms-deliveries")
public class SmsDeliveryController {
    private final SmsDeliveryQuery query;

    public SmsDeliveryController(SmsDeliveryQuery query) {
        this.query = query;
    }

    @GetMapping
    public PageResult<SmsDeliveryQuery.Entry> list(
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
