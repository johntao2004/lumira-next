package com.lumira.platform.common;

import java.util.List;
import java.util.UUID;

/**
 * Storage CAS queue primitive. Caller supplies a transaction; each call fences at most 100 rows.
 */
public interface FileRetentionQueuePort {
    List<UUID> expireTrashed(int retentionDays);
}
