package com.lumira.plugins.directory.users;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only effective quota and proposed role-policy preview for user management. */
@RestController
@com.lumira.platform.modules.ModuleEndpoint("directory")
@RequestMapping("/api/platform/users/{userId}/file-quota")
public class UserFileQuotaController {
    private final UserFileQuotaService files;

    public UserFileQuotaController(UserFileQuotaService files) {
        this.files = files;
    }

    @GetMapping
    public UserFileQuotaService.UserQuotaView get(@PathVariable UUID userId, Authentication actor) {
        return files.userQuota(actor.getName(), userId);
    }

    @GetMapping("/preview")
    public UserFileQuotaService.UserQuotaPreview preview(
            @PathVariable UUID userId,
            @RequestParam(required = false) List<UUID> roleIds,
            Authentication actor) {
        return files.previewUserQuota(
                actor.getName(),
                userId,
                roleIds == null ? new HashSet<>() : new HashSet<>(roleIds));
    }
}
