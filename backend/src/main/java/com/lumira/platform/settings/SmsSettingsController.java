package com.lumira.platform.settings;

import com.lumira.platform.common.BoundedBody;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
@RequestMapping("/api/platform/settings/sms")
public class SmsSettingsController {
    private final SmsSettingsService sms;
    public SmsSettingsController(SmsSettingsService sms) { this.sms = sms; }
    @GetMapping public SmsSettingsService.View read() { return sms.read(); }
    @PutMapping public SmsSettingsService.View save(@BoundedBody(maxBytes = 32768, errorCode = "invalidSms") @RequestBody SmsSettingsService.Input input, Authentication actor) {
        return sms.save(actor.getName(), input);
    }
}
