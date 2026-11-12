package com.lumira.plugins.auditconsole.api;

import com.lumira.platform.common.AuditQueryPort;
import java.time.OffsetDateTime;
import java.util.*;

/** Explicit HTTP response; persistence records remain internal to the feature. */
public record AuditEventResponse(
        UUID id, String actor, String action, UUID targetId, OffsetDateTime createdAt) {
    public static AuditEventResponse from(AuditQueryPort.Event value) {
        if (value == null) return null;
        return new AuditEventResponse(
                value.id(), value.actor(), value.action(), value.targetId(), value.createdAt());
    }
}
