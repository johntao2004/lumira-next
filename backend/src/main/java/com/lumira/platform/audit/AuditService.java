package com.lumira.platform.audit;

import com.lumira.platform.common.*;
import com.lumira.platform.common.AdministrationPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AuditService {
    private final AuditRepository repository;
    private final AdministrationPort guard;

    public AuditService(AuditRepository repository, AdministrationPort guard) {
        this.repository = repository;
        this.guard = guard;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<AuditRepository.Event> list(PageRequest p, String actor) {
        // Platform audit is a global administrative resource, not business data.
        guard.requireGlobal(actor, "platform:audit:read");
        return new PageResult<>(repository.list(p), repository.count(p), p.page(), p.pageSize());
    }
}
