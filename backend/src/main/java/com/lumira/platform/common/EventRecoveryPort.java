package com.lumira.platform.common;

import java.util.UUID;

/** Kernel durable-queue recovery contract; administrative policy belongs to its caller. */
public interface EventRecoveryPort {
    record Replay(UUID id, String status, int attempts, int generation) {}

    Replay replay(UUID id, String actor, String reason, UUID auditOperationId);
}
