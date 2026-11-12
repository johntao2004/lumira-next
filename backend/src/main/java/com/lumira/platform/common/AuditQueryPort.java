package com.lumira.platform.common;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Read-only access to kernel audit storage; plugins cannot delete retained audit records. */
public interface AuditQueryPort {
    record Event(UUID id, String actor, String action, UUID targetId, OffsetDateTime createdAt) {}

    List<Event> queryEvents(PageRequest page);

    long countEvents(PageRequest page);
}
