package com.lumira.plugins.authenticationconsole;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.settings.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("authentication-console")
@RequestMapping("/api/platform/settings/wechat")
public class WechatSettingsController {
    private final WechatSettingsService service;
    private final AdministrationPort guard;

    public WechatSettingsController(WechatSettingsService service, AdministrationPort guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping
    public WechatConfigurationReader.View read(Authentication actor) {
        guard.requirePageGlobal(actor.getName(), PlatformPageId.WECHAT);
        return service.read();
    }

    @PutMapping
    public WechatConfigurationReader.View save(
            @BoundedBody(maxBytes = 16384, errorCode = "invalidWechatSettings") @RequestBody
                    WechatSettingsService.Input input,
            Authentication actor) {
        return service.save(actor.getName(), input);
    }
}
