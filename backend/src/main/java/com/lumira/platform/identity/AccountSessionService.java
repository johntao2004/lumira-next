package com.lumira.platform.identity;

import com.lumira.platform.common.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountSessionService {
    private final AccountSessionRepository repository;
    private final AuditPort audit;
    private final com.lumira.platform.common.AuthorizationContextBoundaryPort authorizationBoundary;

    public AccountSessionService(AccountSessionRepository repository, AuditPort audit) {
        this(repository, audit, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AccountSessionService(
            AccountSessionRepository repository,
            AuditPort audit,
            com.lumira.platform.common.AuthorizationContextBoundaryPort authorizationBoundary) {
        this.repository = repository;
        this.audit = audit;
        this.authorizationBoundary = authorizationBoundary;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<AccountSessionRepository.Session> list(
            String username, String currentId, PageRequest p) {
        long now = System.currentTimeMillis();
        return new PageResult<>(
                repository.list(username, currentId, now, p),
                repository.count(username, currentId, now, p),
                p.page(),
                p.pageSize());
    }

    @Transactional
    public boolean delete(String username, UUID id, String currentId) {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
        boolean current = repository.current(username, id, currentId);
        if (!repository.delete(username, id))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "sessionNotFound");
        audit.append(username, "session.revoke", id);
        return current;
    }

    @Transactional
    public void deleteOthers(String username, String currentId) {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
        for (var id : repository.deleteOthers(username, currentId))
            audit.append(username, "session.revoke", UUID.fromString(id));
    }
}
