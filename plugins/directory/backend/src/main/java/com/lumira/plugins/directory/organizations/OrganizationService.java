package com.lumira.plugins.directory.organizations;

import com.lumira.platform.common.*;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuthorizationContextBoundaryPort;
import com.lumira.platform.common.AuthorizationContextProjectionPort;
import com.lumira.platform.common.UserDirectoryPort;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 在事务和授权快照内维护组织树与用户成员关系，检查动作涉及的目标范围。 Maintain organization trees and memberships within
 * transactions and authorization snapshots, checking action targets.
 */
@Service
public class OrganizationService {
    public record Input(
            UUID parentId,
            String code,
            String name,
            Boolean enabled,
            Long version,
            @JsonDeserialize(using = OrganizationSortNoDeserializer.class) Integer sortNo) {
        public Input(UUID parentId, String code, String name, Boolean enabled, Long version) {
            this(parentId, code, name, enabled, version, null);
        }
    }

    public record MembershipInput(Long version, Set<UUID> organizationIds) {}

    public record Membership(long version, List<UUID> organizationIds) {}

    private final OrganizationRepository repository;
    private final AdministrationPort guard;
    private final UserDirectoryPort users;
    private final AuditPort audit;
    private final AuthorizationContextBoundaryPort authorizationBoundary;
    private final AuthorizationContextProjectionPort authorizationProjection;

    public OrganizationService(
            OrganizationRepository repository,
            AdministrationPort guard,
            UserDirectoryPort users,
            AuditPort audit) {
        this(repository, guard, users, audit, null, null);
    }

    public OrganizationService(
            OrganizationRepository repository,
            AdministrationPort guard,
            UserDirectoryPort users,
            AuditPort audit,
            AuthorizationContextBoundaryPort authorizationBoundary) {
        this(repository, guard, users, audit, authorizationBoundary, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrganizationService(
            OrganizationRepository repository,
            AdministrationPort guard,
            UserDirectoryPort users,
            AuditPort audit,
            AuthorizationContextBoundaryPort authorizationBoundary,
            AuthorizationContextProjectionPort authorizationProjection) {
        this.repository = repository;
        this.guard = guard;
        this.users = users;
        this.audit = audit;
        this.authorizationBoundary = authorizationBoundary;
        this.authorizationProjection = authorizationProjection;
    }

    private void validateContextBoundary() {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
    }

    private static void fail(HttpStatus status, String code) {
        throw new ResponseStatusException(status, code);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<OrganizationRepository.Organization> list(
            PageRequest p, String parent, String actor) {
        return list(p, parent, actor, currentAuthorization(actor));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<OrganizationRepository.Organization> list(
            PageRequest p,
            String parent,
            String actor,
            AuthorizationContextProjectionPort.Snapshot context) {
        validateContextBoundary();
        guard.requirePage(actor, PlatformPageId.ORGANIZATIONS);
        var filter = ParentFilter.parse(parent);
        if (filter.id() != null) repository.findVisible(filter.id(), actor, context);
        return new PageResult<>(
                repository.list(p, filter.active(), filter.id(), actor, context),
                repository.count(p, filter.active(), filter.id(), actor, context),
                p.page(),
                p.pageSize());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<OrganizationRepository.Organization> ancestors(UUID id, String actor) {
        return ancestors(id, actor, currentAuthorization(actor));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<OrganizationRepository.Organization> ancestors(
            UUID id, String actor, AuthorizationContextProjectionPort.Snapshot context) {
        validateContextBoundary();
        guard.requirePage(actor, PlatformPageId.ORGANIZATIONS);
        repository.findVisible(id, actor, context);
        return repository.ancestors(id, actor, context);
    }

    @Transactional(readOnly = true)
    public OrganizationRepository.Organization find(UUID id) {
        return repository.find(id);
    }

    @Transactional(readOnly = true)
    public OrganizationRepository.Organization find(UUID id, String actor) {
        return find(id, actor, currentAuthorization(actor));
    }

    /**
     * 当组织超出操作者组织范围时返回空值，且不将调用方事务标记为仅回滚。 Empty when the organization is outside the actor's
     * organization scope. Does not mark the caller transaction rollback-only.
     */
    public java.util.Optional<OrganizationRepository.Organization> findIfVisible(
            UUID id, String actor) {
        validateContextBoundary();
        return repository.findVisibleIfPresent(id, actor, currentAuthorization(actor));
    }

    @Transactional(readOnly = true)
    public OrganizationRepository.Organization find(
            UUID id, String actor, AuthorizationContextProjectionPort.Snapshot context) {
        validateContextBoundary();
        guard.requirePage(actor, PlatformPageId.ORGANIZATIONS);
        return repository.findVisible(id, actor, context);
    }

    private AuthorizationContextProjectionPort.Snapshot currentAuthorization(String actor) {
        return authorizationProjection == null
                ? null
                : authorizationProjection.current(actor).orElse(null);
    }

    private void validate(UUID id, Input in) {
        if (in.name() == null
                || in.name().isBlank()
                || in.name().strip().length() > 200
                || in.enabled() == null
                || (in.sortNo() != null && in.sortNo() < 0))
            fail(HttpStatus.BAD_REQUEST, "invalidOrganization");
        if (in.parentId() != null) {
            repository.find(in.parentId());
            if (in.parentId().equals(id) || repository.cycles(id, in.parentId()))
                fail(HttpStatus.CONFLICT, "organizationCycle");
        }
    }

    @Transactional
    public OrganizationRepository.Organization create(Input in, String actor) {
        guard.requireOperationActionGlobal(actor, PlatformOperation.ORGANIZATION_CREATE);
        var id = UUID.randomUUID();
        validate(id, in);
        if (in.code() == null || !in.code().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}"))
            fail(HttpStatus.BAD_REQUEST, "invalidOrganization");
        repository.create(
                id,
                in.parentId(),
                in.code(),
                in.name().strip(),
                in.enabled(),
                in.sortNo() == null ? 0 : in.sortNo());
        audit.append(actor, "organization.create", id);
        return repository.find(id);
    }

    @Transactional
    public OrganizationRepository.Organization update(UUID id, Input in, String actor) {
        guard.requireOperationActionGlobal(actor, PlatformOperation.ORGANIZATION_UPDATE);
        var old = repository.find(id);
        if (in.version() == null || old.version() != in.version())
            fail(HttpStatus.CONFLICT, "staleRecord");
        validate(id, in);
        int sortNo = in.sortNo() == null ? old.sortNo() : in.sortNo();
        if (!repository.update(
                id, in.parentId(), in.name().strip(), in.enabled(), sortNo, old.version()))
            fail(HttpStatus.CONFLICT, "staleRecord");
        audit.append(actor, "organization.update", id);
        return repository.find(id);
    }

    @Transactional
    public void delete(UUID id, long version, String actor) {
        guard.requireOperationActionGlobal(actor, PlatformOperation.ORGANIZATION_DELETE);
        var old = repository.find(id);
        if (old.version() != version) fail(HttpStatus.CONFLICT, "staleRecord");
        if (repository.inUse(id)) fail(HttpStatus.CONFLICT, "organizationInUse");
        if (!repository.delete(id, version)) fail(HttpStatus.CONFLICT, "staleRecord");
        audit.append(actor, "organization.delete", id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Membership memberships(UUID id, String actor) {
        validateContextBoundary();
        guard.requirePage(actor, PlatformPageId.USERS);
        guard.requireUser(actor, id, PlatformPageId.USERS.accessPermission());
        String permission = membershipVisibilityPermission(actor);
        return new Membership(
                users.versionSnapshot(id),
                repository.visibleMemberships(id, actor, permission, currentAuthorization(actor)));
    }

    /**
     * 仅具备只读权限时沿用用户可见范围；对具备分配组织权限的操作者不得展示其无权保存的组织，否则编辑器回显该组织并在整体替换保存时被拒绝。 Readers follow user
     * visibility. An actor who can assign organizations must not be shown an organization they
     * cannot save, or the editor echoes it and the replacement is rejected.
     */
    private String membershipVisibilityPermission(String actor) {
        try {
            guard.requireOperation(actor, PlatformOperation.USER_ASSIGN_ORGANIZATIONS);
            return PlatformOperation.USER_ASSIGN_ORGANIZATIONS.permission();
        } catch (ResponseStatusException denied) {
            if (denied.getStatusCode() != HttpStatus.FORBIDDEN) throw denied;
            return PlatformPageId.USERS.accessPermission();
        }
    }

    @Transactional
    public Membership assign(UUID id, MembershipInput input, String actor) {
        guard.requireOperationUser(actor, id, PlatformOperation.USER_ASSIGN_ORGANIZATIONS);
        var version = users.version(id);
        if (input.version() == null || input.version() != version)
            fail(HttpStatus.CONFLICT, "staleRecord");
        if (input.organizationIds() == null
                || input.organizationIds().size() > 100
                || input.organizationIds().stream().anyMatch(Objects::isNull))
            fail(HttpStatus.BAD_REQUEST, "invalidOrganization");
        String permission = PlatformOperation.USER_ASSIGN_ORGANIZATIONS.permission();
        for (var organizationId : input.organizationIds()) {
            if (!repository.coveredOrganization(actor, permission, organizationId))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            repository.find(organizationId);
        }
        var next = new LinkedHashSet<>(input.organizationIds());
        for (var organizationId : repository.memberships(id)) {
            if (!next.contains(organizationId)
                    && !repository.coveredOrganization(actor, permission, organizationId))
                next.add(organizationId);
        }
        repository.memberships(id, next);
        audit.append(actor, "user.organizations", id);
        return new Membership(version + 1, List.copyOf(input.organizationIds()));
    }
}
