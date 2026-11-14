package com.lumira.plugins.directory.sessions;

import com.lumira.platform.common.*;
import com.lumira.platform.common.AuditPort;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OnlineSessionService {
    private final OnlineSessionRepository sessions;
    private final com.lumira.platform.common.AdministrationPort guard;
    private final AuditPort audit;

    public OnlineSessionService(
            OnlineSessionRepository sessions,
            com.lumira.platform.common.AdministrationPort guard,
            AuditPort audit) {
        this.sessions = sessions;
        this.guard = guard;
        this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<OnlineSessionRepository.Entry> list(String actor, PageRequest request) {
        guard.requirePageGlobal(actor, PlatformPageId.ONLINE_USERS);
        return sessions.queryPage(request, System.currentTimeMillis());
    }

    @Transactional
    public void revoke(String actor, UUID id) {
        guard.requireOperationGlobal(actor, PlatformOperation.ONLINE_USER_TERMINATE_SESSION);
        if (!sessions.revoke(id))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "sessionNotFound");
        audit.append(actor, "session.adminRevoke", id);
    }

    @Transactional
    public int revokeUser(String actor, UUID userId) {
        guard.requireOperationGlobal(actor, PlatformOperation.ONLINE_USER_TERMINATE_SESSION);
        int count = sessions.revokeUser(userId);
        if (count > 0) audit.append(actor, "session.adminRevokeUser", userId);
        return count;
    }
}
