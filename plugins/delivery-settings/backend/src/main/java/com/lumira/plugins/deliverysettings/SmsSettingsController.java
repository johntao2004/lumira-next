package com.lumira.plugins.deliverysettings;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.PlatformPageId;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("delivery-settings")
@RequestMapping("/api/platform/settings/sms")
public class SmsSettingsController {
    private final SmsSettingsService sms;
    private final AdministrationPort administration;

    public SmsSettingsController(SmsSettingsService sms, AdministrationPort administration) {
        this.sms = sms;
        this.administration = administration;
    }

    @GetMapping
    public SmsSettingsService.View read(Authentication actor) {
        administration.requirePageGlobal(actor.getName(), PlatformPageId.SMS);
        return sms.read();
    }

    @PutMapping
    public SmsSettingsService.View save(
            @BoundedBody(maxBytes = 32768, errorCode = "invalidSms") @RequestBody
                    SmsSettingsService.Input input,
            Authentication actor) {
        return sms.save(actor.getName(), input);
    }
}
