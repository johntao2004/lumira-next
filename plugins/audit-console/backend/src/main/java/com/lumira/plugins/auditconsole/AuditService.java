package com.lumira.plugins.auditconsole;

import com.lumira.platform.common.*;
import com.lumira.platform.common.AdministrationPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AuditService {
    private final AuditQueryPort repository;
    private final AdministrationPort guard;

    public AuditService(AuditQueryPort repository, AdministrationPort guard) {
        this.repository = repository;
        this.guard = guard;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<AuditQueryPort.Event> list(PageRequest p, String actor) {
        // Platform audit is a global administrative resource, not business data.
        guard.requirePageGlobal(actor, PlatformPageId.AUDIT);
        return new PageResult<>(
                repository.queryEvents(p), repository.countEvents(p), p.page(), p.pageSize());
    }
}
