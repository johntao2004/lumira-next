package com.lumira.plugins.deliverysettings;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.settings.SettingHistoryReader;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("delivery-settings")
@RequestMapping("/api/platform/settings/sms")
public class SmsSettingHistoryController extends DeliverySettingHistoryController {
    public SmsSettingHistoryController(
            SettingHistoryReader history,
            DeliverySettingRollbackService rollback,
            AdministrationPort guard) {
        super("sms", history, rollback, guard);
    }
}
