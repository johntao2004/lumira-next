package com.lumira.platform.identity;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.ContentPolicyExempt;
import com.lumira.platform.modules.ModuleEndpoint;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ModuleEndpoint("identity")
@RequestMapping("/api/account/locale")
public class AccountLocaleController {
    public record LocaleRequest(String locale) {}

    private final AccountLocaleService service;

    public AccountLocaleController(AccountLocaleService service) {
        this.service = service;
    }

    @GetMapping
    public AccountLocaleService.LocaleResponse read(Authentication actor) {
        return service.read(actor.getName());
    }

    @PutMapping
    @ContentPolicyExempt
    public AccountLocaleService.LocaleResponse save(
            Authentication actor,
            @RequestBody @BoundedBody(maxBytes = 512, errorCode = "invalidLocale")
                    LocaleRequest input) {
        return service.save(actor.getName(), input == null ? null : input.locale());
    }
}
