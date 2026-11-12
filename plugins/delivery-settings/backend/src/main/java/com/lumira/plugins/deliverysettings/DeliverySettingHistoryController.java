package com.lumira.plugins.deliverysettings;

import com.lumira.platform.common.*;
import com.lumira.platform.settings.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@com.lumira.platform.modules.ModuleEndpoint("delivery-settings")
public abstract class DeliverySettingHistoryController {
    private final String section;
    private final SettingHistoryReader history;
    private final DeliverySettingRollbackService rollback;
    private final AdministrationPort administration;

    protected DeliverySettingHistoryController(
            String section,
            SettingHistoryReader history,
            DeliverySettingRollbackService rollback,
            AdministrationPort administration) {
        DeliverySettingsSections.operation(section);
        this.section = section;
        this.history = history;
        this.rollback = rollback;
        this.administration = administration;
    }

    @GetMapping("/history")
    public PageResult<SettingHistoryReader.HistoryItem> history(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            Authentication actor) {
        administration.requirePageGlobal(
                actor.getName(), DeliverySettingsSections.operation(section).page());
        return history.history(section, page, pageSize);
    }

    @GetMapping("/history/{version}")
    public SettingHistoryReader.HistoryDetail detail(
            @PathVariable long version, Authentication actor) {
        administration.requirePageGlobal(
                actor.getName(), DeliverySettingsSections.operation(section).page());
        return history.detail(section, version);
    }

    @PostMapping("/rollback")
    public DeliverySettingRollbackService.View restore(
            @BoundedBody(maxBytes = 16384, errorCode = "invalidSettingRevision") @RequestBody
                    DeliverySettingRollbackService.Input input,
            Authentication actor) {
        return rollback.rollback(section, input, actor.getName());
    }
}
