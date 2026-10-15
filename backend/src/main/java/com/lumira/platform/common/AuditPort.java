package com.lumira.platform.common;

import java.util.UUID;

/** Write-side audit contract exposed to modules without leaking the audit query implementation. */
public interface AuditPort {
    void append(String actor, String action, UUID targetId);

    /**
     * Append an audit operation under a caller-selected ID so another durable record can point to
     * that exact operation. Existing plugin implementations remain binary compatible; the host
     * audit repository overrides this method and persists the supplied ID.
     */
    default void append(UUID id, String actor, String action, UUID targetId) {
        append(actor, action, targetId);
    }
}
