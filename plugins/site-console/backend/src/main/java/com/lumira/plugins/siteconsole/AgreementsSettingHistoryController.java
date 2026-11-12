package com.lumira.plugins.siteconsole;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.settings.SettingHistoryReader;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("site-console")
@RequestMapping("/api/platform/settings/agreements")
public class AgreementsSettingHistoryController extends SiteSettingHistoryController {
    public AgreementsSettingHistoryController(
            SettingHistoryReader history,
            SiteSettingRollbackService rollback,
            AdministrationPort guard) {
        super("agreements", history, rollback, guard);
    }
}
