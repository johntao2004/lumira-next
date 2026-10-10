package com.lumira.platform.settings;

import com.lumira.platform.common.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
@RequestMapping("/api/platform/settings/smtp")
public class SmtpSettingsController {
    private final SmtpSettingsService smtp;

    public SmtpSettingsController(SmtpSettingsService smtp) {
        this.smtp = smtp;
    }

    @GetMapping
    public SmtpSettingsService.View read() {
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
