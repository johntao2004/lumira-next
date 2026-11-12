package com.lumira.platform.events;

import com.lumira.platform.common.EventRecoveryPort;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Keeps plugin administration independent of the queue implementation. */
@Service
public final class EventRecoveryAdapter implements EventRecoveryPort {
    private final PlatformEventOutboxStore store;

    public EventRecoveryAdapter(PlatformEventOutboxStore store) {
        this.store = store;
    }

    @Override
    public Replay replay(UUID id, String actor, String reason, UUID auditOperationId) {
        var value = store.replay(id, actor, reason, auditOperationId);
        return new Replay(value.id(), value.status(), value.attempts(), value.generation());
    }
}
