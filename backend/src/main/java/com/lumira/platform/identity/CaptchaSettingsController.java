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
@ContentPolicyExempt
@ModuleEndpoint("identity")
@RequestMapping("/api/platform/settings/captcha")
public class CaptchaSettingsController {
    private final CaptchaSettingsService service;

    public CaptchaSettingsController(CaptchaSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public CaptchaSettingsService.View read(Authentication actor) {
        return service.read(actor.getName());
    }

    @PutMapping
    public CaptchaSettingsService.View save(
            @RequestBody @BoundedBody(maxBytes = 16 * 1024, errorCode = "invalidCaptchaSettings")
                    CaptchaSettingsService.Input input,
            Authentication actor) {
        return service.save(actor.getName(), input);
    }
}
