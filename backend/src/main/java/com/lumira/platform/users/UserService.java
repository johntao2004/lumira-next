package com.lumira.platform.users;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.CsvWriter;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.identity.api.PasswordContract;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private static final int MAX_SYNCHRONOUS_EXPORT_ROWS = 50_000;
    private final com.lumira.platform.common.AdministrationPort guard;
    private final UserRepository repository;
    private final org.springframework.security.crypto.password.PasswordEncoder encoder;
    private final AuditPort audit;
    private final SecurityPolicyPort security;

    @Autowired
    public UserService(
            UserRepository repository,
            org.springframework.security.crypto.password.PasswordEncoder encoder,
            com.lumira.platform.common.AdministrationPort guard,
            AuditPort audit,
            SecurityPolicyPort security) {
        this.repository = repository;
        this.encoder = encoder;
        this.guard = guard;
        this.audit = audit;
        this.security = security;
    }

    public UserService(
            UserRepository repository,
            org.springframework.security.crypto.password.PasswordEncoder encoder,
            com.lumira.platform.common.AdministrationPort guard,
            AuditPort audit) {
        this(repository, encoder, guard, audit, null);
    }

    public record CreateUser(String username, String displayName, String password) {}

    public record UpdateUser(String displayName, Boolean enabled, Long version) {}

    private static String name(String value) {
        if (value == null || value.strip().isEmpty() || value.strip().length() > 200)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidUser");
        return value.strip();
    }

    @Transactional
    public UserSummary create(CreateUser input, String actor) {
        return create(input, actor, PlatformOperation.USER_CREATE);
    }

    @Transactional
    public UserSummary createForAi(CreateUser input, String actor) {
        return create(input, actor, PlatformOperation.AI_CONSOLE_CREATE_USER);
    }

    private UserSummary create(CreateUser input, String actor, PlatformOperation operation) {
        if (input == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidUser");
        String displayName = name(input.displayName());
        var policy =
                security == null ? SecurityPolicyPort.Policy.defaults() : security.currentPolicy();
        if (input.username() == null
                || !input.username().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}")
                || !policy.passwordValid(input.password())
                || !PasswordContract.withinMaximum(input.password()))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidUser");
        var hash = encoder.encode(input.password());
        guard.requireOperationActionGlobal(actor, operation);
        var id = java.util.UUID.randomUUID();
        repository.create(id, input.username(), displayName, hash);
        audit.append(actor, "user.create", id);
        return repository.find(id);
    }

    @Transactional
    public UserSummary update(java.util.UUID id, UpdateUser input, String actor) {
        return update(id, input, actor, PlatformOperation.USER_UPDATE);
    }

    @Transactional
    public UserSummary updateForAi(java.util.UUID id, UpdateUser input, String actor) {
        return update(id, input, actor, PlatformOperation.AI_CONSOLE_UPDATE_USER);
    }

    private UserSummary update(
            java.util.UUID id, UpdateUser input, String actor, PlatformOperation operation) {
        String displayName = name(input.displayName());
        if (input.enabled() == null || input.version() == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidUser");
        guard.requireOperationUser(actor, id, operation);
        var user = repository.find(id);
        if (user.version() != input.version())
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "staleRecord");
        if (!input.enabled()
                && (user.username().equals(actor) || repository.isLastAdministrator(id)))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "protectedAccount");
        repository.update(id, displayName, input.enabled());
        if (!input.enabled()) repository.revokeSessions(user.username());
        audit.append(actor, "user.update", id);
        return repository.find(id);
    }

    /** Apply the same user-scope authorization before a profile-value-only edit. */
    public void requireUpdate(java.util.UUID id, String actor) {
        guard.requireOperationUser(actor, id, PlatformOperation.USER_UPDATE);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserSummary find(java.util.UUID id, String actor) {
        guard.requirePage(actor, PlatformPageId.USERS);
        guard.requireUser(actor, id, PlatformPageId.USERS.accessPermission());
        return repository.findSnapshot(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserSummary findForAi(java.util.UUID id, String actor) {
        guard.requireOperationUser(actor, id, PlatformOperation.AI_CONSOLE_READ_USERS);
        return repository.findSnapshot(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserSummary findForAiAction(
            java.util.UUID id, String actor, PlatformOperation operation) {
        if (operation.page() != PlatformPageId.AI_CONSOLE
                || (operation != PlatformOperation.AI_CONSOLE_READ_USERS
                        && operation != PlatformOperation.AI_CONSOLE_UPDATE_USER
                        && operation != PlatformOperation.AI_CONSOLE_DELETE_USER))
            throw new IllegalArgumentException("Unsupported AI user lookup operation");
        guard.requireOperationUser(actor, id, operation);
        return repository.findSnapshot(id);
    }

    @Transactional
    public void delete(java.util.UUID id, long version, String actor) {
        delete(id, version, actor, PlatformOperation.USER_DELETE);
    }

    @Transactional
    public void deleteForAi(java.util.UUID id, long version, String actor) {
        delete(id, version, actor, PlatformOperation.AI_CONSOLE_DELETE_USER);
    }

    private void delete(
            java.util.UUID id, long version, String actor, PlatformOperation operation) {
        guard.requireOperationUser(actor, id, operation);
        var user = repository.find(id);
        if (user.version() != version)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "staleRecord");
        if (user.username().equals(actor) || repository.isLastAdministrator(id))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "protectedAccount");
        if (repository.softDelete(id, version) != 1)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "staleRecord");
        repository.revokeSessions(user.username());
        audit.append(actor, "user.delete", id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<UserSummary> list(PageRequest request, String actor) {
        return list(request, actor, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<UserSummary> list(
            PageRequest request, String actor, RequestAuthorizationContext authorization) {
        guard.requirePage(actor, PlatformPageId.USERS);
        long total = repository.count(request, actor, authorization);
        return new PageResult<>(
                repository.findPage(request, actor, authorization),
                total,
                request.page(),
                request.pageSize());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<UserSummary> listForAi(PageRequest request, String actor) {
        guard.requireOperation(actor, PlatformOperation.AI_CONSOLE_READ_USERS);
        String permission = PlatformOperation.AI_CONSOLE_READ_USERS.permission();
        long total =
                repository.count(
                        request,
                        actor,
                        null,
                        permission,
                        PlatformPageId.AI_CONSOLE.accessPermission());
        return new PageResult<>(
                repository.findPage(
                        request,
                        actor,
                        null,
                        permission,
                        PlatformPageId.AI_CONSOLE.accessPermission()),
                total,
                request.page(),
                request.pageSize());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] exportCsv(PageRequest request, String actor) {
        return exportCsv(request, actor, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] exportCsv(
            PageRequest request, String actor, RequestAuthorizationContext authorization) {
        guard.requireOperation(actor, PlatformOperation.USER_EXPORT);
        var users =
                repository.findExport(
                        request,
                        actor,
                        MAX_SYNCHRONOUS_EXPORT_ROWS + 1,
                        authorization,
                        PlatformOperation.USER_EXPORT.permission(),
                        PlatformPageId.USERS.accessPermission());
        if (users.size() > MAX_SYNCHRONOUS_EXPORT_ROWS) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "Export is too large; narrow the search");
        }
        var csv = new StringBuilder(Math.max(128, users.size() * 96));
        CsvWriter.row(
                csv,
                List.of(
                        "id",
                        "username",
                        "display_name",
                        "enabled",
                        "version",
                        "file_quota_bytes_override",
                        "file_quota_files_override"));
        for (var user : users) {
            CsvWriter.row(
                    csv,
                    java.util.Arrays.asList(
                            user.id(),
                            user.username(),
                            user.displayName(),
                            user.enabled(),
                            user.version(),
                            user.fileQuotaBytesOverride(),
                            user.fileQuotaFilesOverride()));
        }
        return CsvWriter.utf8(csv);
    }

    public byte[] exportCsv(String search, String actor) {
        return exportCsv(new PageRequest(1, 1, search), actor);
    }
}
