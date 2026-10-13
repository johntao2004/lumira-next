package com.lumira.platform.roles;

import com.lumira.platform.common.FileQuotaPolicyPort;
import com.lumira.platform.files.FileQuota;
import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves each quota dimension from enabled roles, using system settings only as a fallback.
 * Positive byte limits combine by minimum; a zero byte limit is unlimited.
 */
@Service
public class RoleQuotaPolicyAdapter implements FileQuotaPolicyPort {
    private final RoleRepository roles;
    private final FileQuota fallback;

    public RoleQuotaPolicyAdapter(RoleRepository roles, FileQuota fallback) {
        this.roles = roles;
        this.fallback = fallback;
    }

    @Override
    public Limits forUser(UUID userId) {
        return merge(roles.assignedQuotaLimits(userId));
    }

    @Override
    public Limits forRoles(Collection<UUID> roleIds) {
        if (roleIds == null
                || roleIds.size() > 100
                || roleIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidRole");
        }
        var unique = new HashSet<>(roleIds);
        var rows = roles.quotaLimits(unique);
        if (rows.size() != unique.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidRole");
        }
        return merge(rows);
    }

    private Limits merge(Collection<RoleRepository.QuotaLimit> rows) {
        Long roleMaximumBytes = null;
        boolean hasUnlimitedByteRole = false;
        Long roleMaximumFiles = null;
        for (var row : rows) {
            if (!row.enabled()) continue;
            if (row.bytes() != null) {
                if (row.bytes() == 0) hasUnlimitedByteRole = true;
                else roleMaximumBytes = minimum(roleMaximumBytes, row.bytes());
            }
            if (row.files() != null) roleMaximumFiles = minimum(roleMaximumFiles, row.files());
        }
        long maximumBytes =
                roleMaximumBytes != null
                        ? roleMaximumBytes
                        : hasUnlimitedByteRole ? 0 : fallback.maximumBytes();
        long maximumFiles = roleMaximumFiles == null ? fallback.maximumFiles() : roleMaximumFiles;
        return new Limits(maximumBytes, maximumFiles);
    }

    private static Long minimum(Long current, long candidate) {
        return current == null ? candidate : Math.min(current, candidate);
    }
}
