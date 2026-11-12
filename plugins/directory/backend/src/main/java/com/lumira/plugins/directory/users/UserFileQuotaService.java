package com.lumira.plugins.directory.users;

import com.lumira.platform.common.*;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Directory owns target visibility, proposed-role eligibility and quota projections. */
@Service
public class UserFileQuotaService {
    public record UserQuotaView(
            UUID userId,
            String username,
            String displayName,
            long chargedBytes,
            long fileCount,
            long maximumBytes,
            long maximumFiles,
            long defaultMaximumBytes,
            long defaultMaximumFiles,
            long version) {}

    public record UserQuotaPreview(
            long currentMaximumBytes,
            long currentMaximumFiles,
            long proposedMaximumBytes,
            long proposedMaximumFiles,
            long chargedBytes,
            long fileCount,
            long defaultMaximumBytes,
            long defaultMaximumFiles,
            long version) {}

    private final AccountFileStoragePort files;
    private final FileQuotaPolicyPort quotas;
    private final FileQuotaDefaultsPort defaults;
    private final AdministrationPort guard;
    private final RoleAssignmentPreviewPort rolePreview;

    public UserFileQuotaService(
            AccountFileStoragePort files,
            FileQuotaPolicyPort quotas,
            FileQuotaDefaultsPort defaults,
            AdministrationPort guard,
            @Qualifier("roleService") RoleAssignmentPreviewPort rolePreview) {
        this.files = files;
        this.quotas = quotas;
        this.defaults = defaults;
        this.guard = guard;
        this.rolePreview = rolePreview;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserQuotaView userQuota(String actor, UUID userId) {
        guard.requirePage(actor, PlatformPageId.USERS);
        guard.requireUser(actor, userId, PlatformPageId.USERS.accessPermission());
        var owner = files.quotaOwner(userId);
        var used = files.usage(userId);
        var maximum = current(userId);
        var fallback = defaults.current();
        return new UserQuotaView(
                owner.userId(),
                owner.username(),
                owner.displayName(),
                used.chargedBytes(),
                used.fileCount(),
                maximum.maximumBytes(),
                maximum.maximumFiles(),
                fallback.maximumBytes(),
                fallback.maximumFiles(),
                owner.version());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserQuotaPreview previewUserQuota(String actor, UUID userId, Set<UUID> proposedRoleIds) {
        guard.requireOperationUser(actor, userId, PlatformOperation.USER_ASSIGN_ROLES);
        if (rolePreview != null) rolePreview.requirePreviewable(actor, userId, proposedRoleIds);
        var owner = files.quotaOwner(userId);
        var used = files.usage(userId);
        var maximum = current(userId);
        var proposed = quotas.forRoles(proposedRoleIds);
        var fallback = defaults.current();
        return new UserQuotaPreview(
                maximum.maximumBytes(),
                maximum.maximumFiles(),
                proposed.maximumBytes(),
                proposed.maximumFiles(),
                used.chargedBytes(),
                used.fileCount(),
                fallback.maximumBytes(),
                fallback.maximumFiles(),
                owner.version());
    }

    private FileQuotaPolicyPort.Limits current(UUID userId) {
        var value = quotas.forUser(userId);
        if (value.maximumBytes() < 0 || value.maximumFiles() < 0)
            throw new IllegalArgumentException("Negative file quota");
        return value;
    }
}
