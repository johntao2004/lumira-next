package com.lumira.plugins.deliverysettings;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.settings.SettingHistoryReader;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("delivery-settings")
@RequestMapping("/api/platform/settings/smtp")
public class SmtpSettingHistoryController extends DeliverySettingHistoryController {
    public SmtpSettingHistoryController(
            SettingHistoryReader history,
            DeliverySettingRollbackService rollback,
            AdministrationPort guard) {
        super("smtp", history, rollback, guard);
    }
}
