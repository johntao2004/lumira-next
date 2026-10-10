package com.lumira.platform.users;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.CsvWriter;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
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
        guard.require(actor, "platform:users:write");
        guard.requireGlobal(actor, "platform:users:write");
        var id = java.util.UUID.randomUUID();
        repository.create(id, input.username(), displayName, hash);
        audit.append(actor, "user.create", id);
        return repository.find(id);
    }

    @Transactional
    public UserSummary update(java.util.UUID id, UpdateUser input, String actor) {
        String displayName = name(input.displayName());
        if (input.enabled() == null || input.version() == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalidUser");
        guard.require(actor, "platform:users:write");
        guard.requireUser(actor, id, "platform:users:write");
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
        guard.require(actor, "platform:users:write");
        guard.requireUser(actor, id, "platform:users:write");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserSummary find(java.util.UUID id, String actor) {
        guard.require(actor, "platform:users:read");
        guard.requireUser(actor, id, "platform:users:read");
        return repository.findSnapshot(id);
    }

    @Transactional
    public void delete(java.util.UUID id, long version, String actor) {
        guard.require(actor, "platform:users:write");
        guard.requireUser(actor, id, "platform:users:write");
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
        guard.require(actor, "platform:users:read");
        long total = repository.count(request, actor, authorization);
        return new PageResult<>(
                repository.findPage(request, actor, authorization),
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
        guard.require(actor, "platform:users:read");
        var users =
                repository.findExport(
                        request, actor, MAX_SYNCHRONOUS_EXPORT_ROWS + 1, authorization);
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
