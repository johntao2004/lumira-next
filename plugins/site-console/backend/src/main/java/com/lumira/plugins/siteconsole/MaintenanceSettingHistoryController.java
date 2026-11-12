package com.lumira.plugins.siteconsole;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.settings.SettingHistoryReader;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("site-console")
@RequestMapping("/api/platform/settings/maintenance")
public class MaintenanceSettingHistoryController extends SiteSettingHistoryController {
    public MaintenanceSettingHistoryController(
            SettingHistoryReader history,
            SiteSettingRollbackService rollback,
            AdministrationPort guard) {
        super("maintenance", history, rollback, guard);
    }
}
