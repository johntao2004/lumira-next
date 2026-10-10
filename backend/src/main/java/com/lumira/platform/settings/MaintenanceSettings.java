package com.lumira.platform.settings;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record MaintenanceSettings(
        Boolean enabled, String title, String message, Instant endAt, List<UUID> allowedRoleIds) {
    public static MaintenanceSettings defaults() {
        return new MaintenanceSettings(false, "", "", null, List.of());
    }

    public boolean activeAt(Instant now) {
        return Boolean.TRUE.equals(enabled) && (endAt == null || now.isBefore(endAt));
    }

    public void validate() {
        if (enabled == null
                || title == null
                || title.length() > 200
                || message == null
                || message.length() > 2000
                || allowedRoleIds == null
                || allowedRoleIds.size() > 100
                || allowedRoleIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(allowedRoleIds).size() != allowedRoleIds.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidMaintenance");
    }
}
