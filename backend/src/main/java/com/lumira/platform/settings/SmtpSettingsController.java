package com.lumira.platform.settings;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.PlatformPageId;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
@RequestMapping("/api/platform/settings/smtp")
public class SmtpSettingsController {
    private final SmtpSettingsService smtp;
    private final AdministrationPort administration;

    public SmtpSettingsController(SmtpSettingsService smtp, AdministrationPort administration) {
        this.smtp = smtp;
        this.administration = administration;
    }

    @GetMapping
    public SmtpSettingsService.View read(Authentication actor) {
        administration.requirePageGlobal(actor.getName(), PlatformPageId.SMTP);
        return smtp.read();
    }

    @PutMapping
    public SmtpSettingsService.View save(
            @BoundedBody(maxBytes = 32768, errorCode = "invalidSmtp") @RequestBody
                    SmtpSettingsService.Input input,
            Authentication actor) {
        return smtp.save(actor.getName(), input);
    }
}
