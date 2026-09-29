package com.lumira.platform.sms;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("communication")
@RequestMapping("/api/platform/settings/sms")
public class SmsTestController {
    private final SmsDeliveryTestService test;

    public SmsTestController(SmsDeliveryTestService test) {
        this.test = test;
    }

    @PostMapping("/test")
    public SmsDeliveryTestService.Result sendLocalTest(Authentication actor) {
        return test.sendLocalTest(actor.getName());
    }
}
