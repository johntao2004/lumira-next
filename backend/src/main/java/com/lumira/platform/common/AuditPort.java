package com.lumira.platform.common;

import java.util.UUID;

/** Write-side audit contract exposed to modules without leaking the audit query implementation. */
public interface AuditPort {
    void append(String actor, String action, UUID targetId);
}
