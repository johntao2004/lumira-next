package com.lumira.plugins.authenticationconsole;

import com.lumira.platform.common.*;
import com.lumira.platform.settings.SettingHistoryReader;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("authentication-console")
@RequestMapping("/api/platform/settings/passkey")
public class PasskeySettingHistoryController {
    private final SettingHistoryReader history;
    private final PasskeySettingRollbackService rollback;
    private final AdministrationPort guard;

    public PasskeySettingHistoryController(
            SettingHistoryReader history,
            PasskeySettingRollbackService rollback,
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
        guard.requirePageGlobal(actor.getName(), PlatformPageId.PASSKEY);
        return history.history("passkey", page, pageSize);
    }

    @GetMapping("/history/{version}")
    public SettingHistoryReader.HistoryDetail detail(
            @PathVariable long version, Authentication actor) {
        guard.requirePageGlobal(actor.getName(), PlatformPageId.PASSKEY);
        return history.detail("passkey", version);
    }

    @PostMapping("/rollback")
    public PasskeySettingRollbackService.View restore(
            @BoundedBody(maxBytes = 16384, errorCode = "invalidSettingRevision") @RequestBody
                    PasskeySettingRollbackService.Input input,
            Authentication actor) {
        return rollback.rollback("passkey", input, actor.getName());
    }
}
