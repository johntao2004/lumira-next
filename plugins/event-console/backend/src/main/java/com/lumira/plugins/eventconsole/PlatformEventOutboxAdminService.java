/**
 * 校验全局重放权限和必填原因，将死信重置、审批记录与审计一并提交，保证人工恢复可追溯。 Checks global replay authority and a required reason,
 * committing dead-letter resets with approval and audit records to keep manual recovery traceable.
 */
package com.lumira.plugins.eventconsole;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.EventRecoveryPort;
import com.lumira.platform.common.PlatformOperation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 持久化事件恢复的管理适配器；此 API 从不接收或返回事件载荷。 Administrative adapter for durable event recovery; payloads
 * never enter this API.
 */
@Service
public class PlatformEventOutboxAdminService {
    public static final String REPLAY = PlatformOperation.EVENT_OUTBOX_REPLAY.permission();

    private final AdministrationPort guard;
    private final EventRecoveryPort store;
    private final PlatformEventReplayApprovalRepository approvals;
    private final com.lumira.platform.common.AuditPort audit;

    public PlatformEventOutboxAdminService(
            AdministrationPort guard,
            EventRecoveryPort store,
            PlatformEventReplayApprovalRepository approvals,
            com.lumira.platform.common.AuditPort audit) {
        this.guard = guard;
        this.store = store;
        this.approvals = approvals;
        this.audit = audit;
    }

    @Transactional
    public EventRecoveryPort.Replay replay(UUID id, String actor, String reason) {
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
