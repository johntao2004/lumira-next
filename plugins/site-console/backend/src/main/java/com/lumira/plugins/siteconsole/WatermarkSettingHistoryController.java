package com.lumira.plugins.siteconsole;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.settings.SettingHistoryReader;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("site-console")
@RequestMapping("/api/platform/settings/watermark")
public class WatermarkSettingHistoryController extends SiteSettingHistoryController {
    public WatermarkSettingHistoryController(
            SettingHistoryReader history,
            SiteSettingRollbackService rollback,
            AdministrationPort guard) {
        super("watermark", history, rollback, guard);
    }
}
