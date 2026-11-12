package com.lumira.plugins.siteconsole;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.settings.SettingHistoryReader;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("site-console")
@RequestMapping("/api/platform/settings/branding")
public class BrandingSettingHistoryController extends SiteSettingHistoryController {
    public BrandingSettingHistoryController(
            SettingHistoryReader history,
            SiteSettingRollbackService rollback,
            AdministrationPort guard) {
        super("branding", history, rollback, guard);
    }
}
