package com.lumira.platform.mail;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("communication")
@RequestMapping("/api/platform/settings/smtp")
public class SmtpTestController {
    private final MailDeliveryTestService test;

    public SmtpTestController(MailDeliveryTestService test) {
        this.test = test;
    }

    @PostMapping("/test")
    public MailDeliveryTestService.Result sendLocalTest(Authentication actor) {
        return test.sendLocalTest(actor.getName());
    }
}
