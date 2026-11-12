package com.lumira.plugins.siteconsole;

import com.lumira.platform.common.*;
import com.lumira.platform.settings.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@com.lumira.platform.modules.ModuleEndpoint("site-console")
public abstract class SiteSettingHistoryController {
    private final String section;
    private final SettingHistoryReader history;
    private final SiteSettingRollbackService rollback;
    private final AdministrationPort administration;

    protected SiteSettingHistoryController(
            String section,
            SettingHistoryReader history,
            SiteSettingRollbackService rollback,
            AdministrationPort administration) {
        SiteSettingsSections.operation(section);
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
                actor.getName(), SiteSettingsSections.operation(section).page());
        return history.history(section, page, pageSize);
    }

    @GetMapping("/history/{version}")
    public SettingHistoryReader.HistoryDetail detail(
            @PathVariable long version, Authentication actor) {
        administration.requirePageGlobal(
                actor.getName(), SiteSettingsSections.operation(section).page());
        return history.detail(section, version);
    }

    @PostMapping("/rollback")
    public SiteSettingRollbackService.View restore(
            @BoundedBody(maxBytes = 16384, errorCode = "invalidSettingRevision") @RequestBody
                    SiteSettingRollbackService.Input input,
            Authentication actor) {
        return rollback.rollback(section, input, actor.getName());
    }
}
