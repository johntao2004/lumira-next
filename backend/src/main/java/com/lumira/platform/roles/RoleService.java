package com.lumira.platform.roles;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.AuthenticationReachabilityPort;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoleService {
    public static Set<String> permissions() {
        var result = new java.util.HashSet<>(PERMISSIONS);
        result.addAll(com.lumira.platform.modules.PluginPermissions.values());
        return Set.copyOf(result);
    }

    public static final Set<String> PERMISSIONS =
            new java.util.concurrent.CopyOnWriteArraySet<>(
                    Set.of(
                            "platform:feedback:read",
                            "platform:feedback:create",
                            "platform:feedback:manage",
                            "platform:sensitive-words:read",
                            "platform:sensitive-words:write",
                            "platform:sensitive-words:import",
                            "platform:localization:read",
                            "platform:localization:write",
                            "platform:localization:import",
                            "platform:modules:read",
                            "platform:modules:write",
                            "platform:events:write",
                            "platform:users:read",
                            "platform:users:write",
                            "platform:roles:read",
                            "platform:roles:write",
                            "platform:roles:preview",
                            "platform:organizations:read",
                            "platform:organizations:write",
                            "platform:audit:read",
                            "platform:dictionaries:read",
                            "platform:dictionaries:write",
                            "platform:settings:read",
                            "platform:settings:write",
                            "platform:navigation:read",
                            "platform:navigation:write",
                            "platform:files:read",
                            "platform:files:write",
                            "platform:notifications:read",
                            "platform:notifications:write"));

    public record RoleInput(
            String code,
            String name,
            Boolean enabled,
            Long version,
            Set<String> authorities,
            String dataScope,
            Long fileQuotaBytesLimit,
            Long fileQuotaFilesLimit) {}

    public record Assignment(Long version, Set<UUID> roleIds) {}

    public record UserRoles(long version, List<UUID> roleIds) {}

    private final com.lumira.platform.common.AdministrationPort guard;
    private final RoleRepository roles;
    private final com.lumira.platform.common.UserDirectoryPort users;
    private final com.lumira.platform.common.AuditPort audit;
    private final AuthenticationPolicyWriteLock policyLock;
    private final EffectiveAuthenticationPolicyPort authenticationPolicies;
    private final JdbcClient jdbc;
    private final GlobalAuthenticationPolicyPort globalAuthenticationPolicy;
    private final AuthenticationReachabilityPort authenticationReachability;
    private static final long MAX_SAFE_QUOTA = 9_007_199_254_740_991L;

    public RoleService(
            RoleRepository roles,
            com.lumira.platform.common.UserDirectoryPort users,
            com.lumira.platform.common.AdministrationPort guard,
            com.lumira.platform.common.AuditPort audit) {
        this(roles, users, guard, audit, null, null, null, null, null);
    }

    @Autowired
    public RoleService(
            RoleRepository roles,
            com.lumira.platform.common.UserDirectoryPort users,
            com.lumira.platform.common.AdministrationPort guard,
            com.lumira.platform.common.AuditPort audit,
            AuthenticationPolicyWriteLock policyLock,
            EffectiveAuthenticationPolicyPort authenticationPolicies,
            JdbcClient jdbc,
            GlobalAuthenticationPolicyPort globalAuthenticationPolicy,
            AuthenticationReachabilityPort authenticationReachability) {
        this.roles = roles;
        this.users = users;
        this.guard = guard;
        this.audit = audit;
        this.policyLock = policyLock;
        this.authenticationPolicies = authenticationPolicies;
        this.jdbc = jdbc;
        this.globalAuthenticationPolicy = globalAuthenticationPolicy;
        this.authenticationReachability = authenticationReachability;
    }

    public RoleService(
            RoleRepository roles,
            com.lumira.platform.common.UserDirectoryPort users,
            com.lumira.platform.common.AdministrationPort guard,
            com.lumira.platform.common.AuditPort audit,
            AuthenticationPolicyWriteLock policyLock,
            EffectiveAuthenticationPolicyPort authenticationPolicies,
            RoleAuthenticationPolicyRepository roleAuthenticationPolicies,
            JdbcClient jdbc) {
        this(roles, users, guard, audit, policyLock, authenticationPolicies, jdbc, null, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<RoleRepository.Role> list(PageRequest p) {
        return new PageResult<>(roles.list(p), roles.count(p), p.page(), p.pageSize());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RoleRepository.Role find(UUID id) {
        return roles.find(id);
    }

    private static void fail(HttpStatus status, String code) {
        throw new ResponseStatusException(status, code);
    }

    private Set<String> begin(String actor) {
        var held = guard.require(actor, "platform:roles:write");
        guard.requireGlobal(actor, "platform:roles:write");
        return held;
    }

    private void validateInput(RoleInput in) {
        if (in.name() == null
                || in.name().isBlank()
                || in.name().strip().length() > 200
                || in.enabled() == null
                || in.authorities() == null
                || in.authorities().stream().anyMatch(Objects::isNull))
            fail(HttpStatus.BAD_REQUEST, "invalidRole");
        if (!validQuota(in.fileQuotaBytesLimit()) || !validQuota(in.fileQuotaFilesLimit()))
            fail(HttpStatus.BAD_REQUEST, "invalidFileQuota");
        if (in.dataScope() != null
                && !Set.of("SELF", "ORGANIZATION", "SUBTREE", "ALL").contains(in.dataScope()))
            fail(HttpStatus.BAD_REQUEST, "invalidRole");
        if (!permissions().containsAll(in.authorities()))
            fail(HttpStatus.BAD_REQUEST, "invalidRole");
    }

    private void validateHeldAuthorities(RoleInput in, Set<String> held) {
        if (!held.containsAll(in.authorities())) fail(HttpStatus.FORBIDDEN, "forbidden");
    }

    /** A manager may re-delegate only permissions they hold across every recipient scope. */
    private void requireDelegation(String actor, Collection<String> authorities) {
        for (String authority : new TreeSet<>(authorities)) guard.requireGlobal(actor, authority);
    }

    private static Set<String> unionAuthorities(Collection<String> left, Collection<String> right) {
        var result = new HashSet<>(left);
        result.addAll(right);
        return result;
    }

    private static boolean validQuota(Long value) {
        return value == null || (value >= 0 && value <= MAX_SAFE_QUOTA);
    }

    private void protect() {
        if (!roles.hasAdministrators()) fail(HttpStatus.CONFLICT, "protectedAccount");
    }

    @Transactional
    public RoleRepository.Role create(RoleInput in, String actor) {
        validateInput(in);
        if (in.code() == null || !in.code().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}"))
            fail(HttpStatus.BAD_REQUEST, "invalidRole");
        var held = begin(actor);
        validateHeldAuthorities(in, held);
        requireDelegation(actor, in.authorities());
        var id = UUID.randomUUID();
        roles.create(
                id,
                in.code(),
                in.name().strip(),
                in.enabled(),
                in.dataScope() == null ? "SELF" : in.dataScope(),
                in.fileQuotaBytesLimit(),
                in.fileQuotaFilesLimit());
        roles.authorities(id, in.authorities());
        audit.append(actor, "role.create", id);
        return roles.find(id);
    }

    @Transactional
    public RoleRepository.Role update(UUID id, RoleInput in, String actor) {
        validateInput(in);
        // Authentication policy lock must precede AdministrationGuard's session boundary.
        lockPolicyMutation();
        var held = begin(actor);
        validateHeldAuthorities(in, held);
        lockRegistrationPolicyMutation();
        if (jdbc != null)
            jdbc.sql("SELECT id FROM platform_role WHERE id=:id FOR UPDATE")
                    .param("id", id)
                    .query(UUID.class)
                    .optional();
        var old = roles.find(id);
        if (in.version() == null || old.version() != in.version())
            fail(HttpStatus.CONFLICT, "staleRecord");
        if (!held.containsAll(old.authorities())) fail(HttpStatus.FORBIDDEN, "forbidden");
        requireDelegation(actor, unionAuthorities(old.authorities(), in.authorities()));
        if (isDefaultRegistrationRole(id)
                && (!Boolean.TRUE.equals(in.enabled())
                        || !"SELF".equals(in.dataScope() == null ? old.dataScope() : in.dataScope())
                        || !Set.of("platform:feedback:read", "platform:feedback:create")
                                .containsAll(in.authorities())))
            fail(HttpStatus.CONFLICT, "registrationRoleInUse");
        roles.update(
                id,
                in.name().strip(),
                in.enabled(),
                in.dataScope() == null ? old.dataScope() : in.dataScope(),
                in.fileQuotaBytesLimit(),
                in.fileQuotaFilesLimit());
        roles.authorities(id, in.authorities());
        if (Boolean.TRUE.equals(in.enabled())) validateAssignedPoliciesForRole(id);
        protect();
        audit.append(actor, "role.update", id);
        return roles.find(id);
    }

    @Transactional
    public void delete(UUID id, long version, String actor) {
        lockPolicyMutation();
        var held = begin(actor);
        lockRegistrationPolicyMutation();
        if (jdbc != null)
            jdbc.sql("SELECT id FROM platform_role WHERE id=:id FOR UPDATE")
                    .param("id", id)
                    .query(UUID.class)
                    .optional();
        var role = roles.find(id);
        if (role.version() != version) fail(HttpStatus.CONFLICT, "staleRecord");
        if (!held.containsAll(role.authorities())) fail(HttpStatus.FORBIDDEN, "forbidden");
        requireDelegation(actor, role.authorities());
        if (isDefaultRegistrationRole(id)) fail(HttpStatus.CONFLICT, "registrationRoleInUse");
        if (roles.assigned(id)) fail(HttpStatus.CONFLICT, "roleInUse");
        roles.delete(id);
        audit.append(actor, "role.delete", id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserRoles userRoles(UUID id, String actor) {
        guard.requireUser(actor, id, "platform:roles:read");
        return new UserRoles(users.versionSnapshot(id), roles.userRoles(id));
    }

    @Transactional
    public UserRoles assign(UUID id, Assignment in, String actor) {
        if (in.roleIds() == null
                || in.roleIds().size() > 100
                || in.roleIds().stream().anyMatch(Objects::isNull))
            fail(HttpStatus.BAD_REQUEST, "invalidRole");
        lockPolicyMutation();
        var held = begin(actor);
        if (jdbc != null)
            jdbc.sql("SELECT id FROM platform_user WHERE id=:id AND deleted_at IS NULL FOR UPDATE")
                    .param("id", id)
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(
                            () ->
                                    new ResponseStatusException(
                                            HttpStatus.NOT_FOUND, "userNotFound"));
        var version = users.version(id);
        if (in.version() == null || version != in.version())
            fail(HttpStatus.CONFLICT, "staleRecord");
        var touched = new HashSet<>(roles.userRoles(id));
        touched.addAll(in.roleIds());
        var affectedAuthorities = new HashSet<String>();
        for (var roleId : touched) {
            var role = roles.find(roleId);
            if (!held.containsAll(role.authorities())) fail(HttpStatus.FORBIDDEN, "forbidden");
            affectedAuthorities.addAll(role.authorities());
        }
        requireDelegation(actor, affectedAuthorities);
        roles.assign(id, in.roleIds());
        validateAssignedPolicies(id);
        protect();
        audit.append(actor, "user.roles", id);
        return new UserRoles(version + 1, roles.userRoles(id));
    }

    private void lockPolicyMutation() {
        if (policyLock != null) policyLock.lockForUpdate();
    }

    private void lockRegistrationPolicyMutation() {
        if (jdbc != null)
            jdbc.sql("SELECT id FROM platform_registration_policy WHERE id=1 FOR SHARE")
                    .query(Integer.class)
                    .optional();
    }

    private boolean isDefaultRegistrationRole(UUID roleId) {
        return jdbc != null
                && jdbc.sql(
                                "SELECT EXISTS(SELECT 1 FROM platform_registration_policy WHERE id=1 AND default_role_id=:id)")
                        .param("id", roleId)
                        .query(Boolean.class)
                        .single();
    }

    private void validateAssignedPolicies(UUID userId) {
        if (authenticationPolicies == null) return;
        var effective = authenticationPolicies.resolve(userId);
        if (effective.usableMethods().isEmpty())
            fail(HttpStatus.CONFLICT, "authenticationPolicyUnreachable");
        if (effective.mfaRequired() && !authenticationPolicies.secondFactorAvailable())
            fail(HttpStatus.CONFLICT, "secondFactorUnavailable");
    }

    private void validateAssignedPoliciesForRole(UUID roleId) {
        if (globalAuthenticationPolicy == null
                || authenticationReachability == null
                || authenticationPolicies == null) return;
        var globalPolicy = globalAuthenticationPolicy.current();
        if (!authenticationReachability.assignedAccountsReachable(
                roleId, globalPolicy.enabledMethods()))
            fail(HttpStatus.CONFLICT, "authenticationPolicyUnreachable");
        boolean factorRequired =
                globalPolicy.mfaRequired()
                        || authenticationReachability.assignedAccountsRequireSecondFactor(roleId);
        if (factorRequired && !authenticationPolicies.secondFactorAvailable())
            fail(HttpStatus.CONFLICT, "secondFactorUnavailable");
    }
}
