package com.lumira.platform.events;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.PlatformOperation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Administrative adapter for durable event recovery; payloads never enter this API. */
@Service
public class PlatformEventOutboxAdminService {
    public static final String REPLAY = PlatformOperation.EVENT_OUTBOX_REPLAY.permission();

    private final AdministrationPort guard;
    private final PlatformEventOutboxStore store;
    private final PlatformEventReplayApprovalRepository approvals;
    private final com.lumira.platform.common.AuditPort audit;

    public PlatformEventOutboxAdminService(
            AdministrationPort guard,
            PlatformEventOutboxStore store,
            PlatformEventReplayApprovalRepository approvals,
            com.lumira.platform.common.AuditPort audit) {
        this.guard = guard;
        this.store = store;
        this.approvals = approvals;
        this.audit = audit;
    }

    @Transactional
    public PlatformEventOutboxStore.Replay replay(UUID id, String actor, String reason) {
        guard.requireOperationGlobal(actor, PlatformOperation.EVENT_OUTBOX_REPLAY);
        if (id == null || reason == null || reason.isBlank() || reason.strip().length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidEventReplay");
        }
        try {
            UUID auditOperationId = UUID.randomUUID();
            var replay = store.replay(id, actor, reason, auditOperationId);
            approvals.append(auditOperationId, id, actor, reason);
            audit.append(auditOperationId, actor, "event.replay.approved", id);
            return replay;
        } catch (ResponseStatusException failure) {
            throw failure;
        }
    }
}
