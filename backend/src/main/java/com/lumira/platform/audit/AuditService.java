/**
 * 以全局审计页面权限控制平台审计查询，并在同一只读快照中读取记录和总数，保持分页一致。 Requires global audit-page authority and reads records
 * and totals from one read-only snapshot to keep platform-audit pagination consistent.
 */
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
        // 平台审计属于全局管理资源，不属于业务数据。
        // Platform audit is a global administrative resource, not business data.
        guard.requirePageGlobal(actor, PlatformPageId.AUDIT);
        return new PageResult<>(repository.list(p), repository.count(p), p.page(), p.pageSize());
    }
}
