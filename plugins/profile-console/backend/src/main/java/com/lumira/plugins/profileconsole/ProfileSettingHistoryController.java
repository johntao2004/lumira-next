package com.lumira.plugins.profileconsole;

import com.lumira.platform.common.*;
import com.lumira.platform.settings.SettingHistoryReader;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("profile-console")
@RequestMapping("/api/platform/settings/profile-fields")
public class ProfileSettingHistoryController {
    private final SettingHistoryReader history;
    private final ProfileSettingRollbackService rollback;
    private final AdministrationPort guard;

    public ProfileSettingHistoryController(
            SettingHistoryReader history,
            ProfileSettingRollbackService rollback,
            AdministrationPort guard) {
        this.history = history;
        this.rollback = rollback;
        this.guard = guard;
    }

    @GetMapping("/history")
    public PageResult<SettingHistoryReader.HistoryItem> history(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            Authentication actor) {
        guard.requirePageGlobal(actor.getName(), PlatformPageId.PROFILE_FIELDS);
        return history.history("profile-fields", page, pageSize);
    }

    @GetMapping("/history/{version}")
    public SettingHistoryReader.HistoryDetail detail(
            @PathVariable long version, Authentication actor) {
        guard.requirePageGlobal(actor.getName(), PlatformPageId.PROFILE_FIELDS);
        return history.detail("profile-fields", version);
    }

    @PostMapping("/rollback")
    public ProfileSettingRollbackService.View restore(
            @BoundedBody(maxBytes = 16384, errorCode = "invalidSettingRevision") @RequestBody
                    ProfileSettingRollbackService.Input input,
            Authentication actor) {
        return rollback.rollback(input, actor.getName());
    }
}
