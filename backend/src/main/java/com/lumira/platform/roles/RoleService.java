package com.lumira.platform.roles;

import com.lumira.platform.common.*;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.identity.api.AuthenticationReachabilityPort;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
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
        result.addAll(pluginPermissions());
        return Set.copyOf(result);
    }

    private static Set<String> pluginPermissions() {
        var result = new HashSet<>(com.lumira.platform.modules.PluginPermissions.values());
        result.removeAll(PERMISSIONS);
        return Set.copyOf(result);
    }

    public static final Set<String> PERMISSIONS = PlatformPermissionCatalog.permissions();

    public record PermissionCatalogPage(
            String id,
            String path,
            String title,
            String navigationGroup,
            int navigationOrder,
            String accessPermission,
            List<String> operationPermissions,
            String accessScope,
            Map<String, String> operationScopes,
            String mappingStatus) {
        public PermissionCatalogPage {
            operationPermissions =
                    List.copyOf(operationPermissions == null ? List.of() : operationPermissions);
            accessScope = accessScope == null ? "global" : accessScope;
            operationScopes = Map.copyOf(operationScopes == null ? Map.of() : operationScopes);
        }

        /** Compatibility fallback for catalog entries without a route-to-permission mapping. */
        public PermissionCatalogPage(String path, String title) {
            this(
                    pageIdFromPath(path),
                    path,
                    title,
                    "unassigned",
                    0,
                    null,
                    List.of(),
                    "global",
                    Map.of(),
                    "UNASSIGNED");
        }
    }

    public record PermissionCatalogPermission(
            String code, String label, List<String> requires, boolean editable, boolean retired) {
        public PermissionCatalogPermission {
            requires = List.copyOf(requires == null ? List.of() : requires);
        }

        public PermissionCatalogPermission(String code, String label) {
            this(code, label, List.of(), false, false);
        }

        public PermissionCatalogPermission(String code, String label, List<String> requires) {
            this(code, label, requires, false, false);
        }
    }

    public record PermissionCatalogGroup(
            String id,
            String title,
            String source,
            List<PermissionCatalogPage> pages,
            List<PermissionCatalogPermission> permissions,
            int catalogVersion) {}

    /**
     * The final component is a request-only compatibility sentinel. It accepts only null or an
     * empty set; non-empty values are rejected so a stale client cannot request legacy projection
     * behavior.
     */
    public record RoleInput(
            String code,
            String name,
            Boolean enabled,
            Long version,
            Set<String> authorities,
            String dataScope,
            Long fileQuotaBytesLimit,
            Long fileQuotaFilesLimit,
            Set<String> legacyProjectionPermissions) {
        public RoleInput(
                String code,
                String name,
                Boolean enabled,
                Long version,
                Set<String> authorities,
                String dataScope,
                Long fileQuotaBytesLimit,
                Long fileQuotaFilesLimit) {
            this(
                    code,
                    name,
                    enabled,
                    version,
                    authorities,
                    dataScope,
                    fileQuotaBytesLimit,
                    fileQuotaFilesLimit,
                    null);
        }
    }

    public record Assignment(Long version, Set<UUID> roleIds) {}

    public record UserRoles(long version, List<UUID> roleIds) {}

    public record AssignmentRoleOption(
            UUID id,
            String code,
            String name,
            boolean enabled,
            Long fileQuotaBytesLimit,
            Long fileQuotaFilesLimit,
            boolean assignable) {}

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
    public PageResult<RoleRepository.Role> list(PageRequest p, String actor) {
        if (!hasPage(actor, PlatformPageId.ROLES)) {
            guard.requirePage(actor, PlatformPageId.USERS);
            if (!hasOperation(actor, PlatformOperation.USER_READ_ROLES)
                    && !hasOperation(actor, PlatformOperation.USER_ASSIGN_ROLES))
                fail(HttpStatus.FORBIDDEN, "forbidden");
        }
        return list(p);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<AssignmentRoleOption> assignmentOptions(
            UUID userId, PageRequest page, String actor) {
        boolean canAssign = hasUserOperation(actor, userId, PlatformOperation.USER_ASSIGN_ROLES);
        if (!canAssign && !hasUserOperation(actor, userId, PlatformOperation.USER_READ_ROLES))
            fail(HttpStatus.FORBIDDEN, "forbidden");

        Set<String> delegableAuthorities = Set.of();
        if (canAssign) {
            Set<String> globalAuthorities = globalAuthorities(actor);
            var delegable = new HashSet<>(permissions());
            delegable.retainAll(globalAuthorities);
            if (globalAuthorities.contains("platform:modules:install"))
                delegable.addAll(pluginPermissions());
            delegableAuthorities = Set.copyOf(delegable);
        }

        return roles.assignmentOptions(
                        page, Set.copyOf(roles.userRoles(userId)), delegableAuthorities, canAssign)
                .map(
                        option ->
                                new AssignmentRoleOption(
                                        option.id(),
                                        option.code(),
                                        option.name(),
                                        option.enabled(),
                                        option.fileQuotaBytesLimit(),
                                        option.fileQuotaFilesLimit(),
                                        option.assignable()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RoleRepository.Role find(UUID id) {
        return roles.find(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RoleRepository.Role find(UUID id, String actor) {
        guard.requirePageGlobal(actor, PlatformPageId.ROLES);
        return find(id);
    }

    private static void fail(HttpStatus status, String code) {
        throw new ResponseStatusException(status, code);
    }

    private Set<String> begin(String actor, PlatformOperation operation) {
        return guard.requireOperationGlobal(actor, operation);
    }

    private void validateInput(RoleInput in) {
        if (in.name() == null
                || in.name().isBlank()
                || in.name().strip().length() > 200
                || in.enabled() == null
                || in.authorities() == null
                || in.authorities().stream().anyMatch(Objects::isNull)
                || (in.legacyProjectionPermissions() != null
                        && in.legacyProjectionPermissions().stream().anyMatch(Objects::isNull)))
            fail(HttpStatus.BAD_REQUEST, "invalidRole");
        if (in.legacyProjectionPermissions() != null && !in.legacyProjectionPermissions().isEmpty())
            fail(HttpStatus.BAD_REQUEST, "invalidRole");
        if (!validQuota(in.fileQuotaBytesLimit()) || !validQuota(in.fileQuotaFilesLimit()))
            fail(HttpStatus.BAD_REQUEST, "invalidFileQuota");
        if (in.dataScope() != null
                && !Set.of("SELF", "ORGANIZATION", "SUBTREE", "ALL").contains(in.dataScope()))
            fail(HttpStatus.BAD_REQUEST, "invalidRole");
    }

    private void validateAssignableAuthorities(
            Collection<String> requested, Collection<String> retained) {
        var allowed = new HashSet<>(permissions());
        allowed.addAll(retained);
        if (!allowed.containsAll(requested)) fail(HttpStatus.FORBIDDEN, "forbidden");
    }

    /**
     * New platform grants require global possession; installer can bootstrap active plugin codes.
     */
    private void requireDelegation(
            String actor, UUID targetRoleId, Collection<String> authorities) {
        Set<String> pluginPermissions = pluginPermissions();
        var pluginBootstrap = new TreeSet<String>();
        for (String authority : new TreeSet<>(authorities)) {
            if (PERMISSIONS.contains(authority) || !pluginPermissions.contains(authority)) {
                guard.requireGlobal(actor, authority);
            } else if (!guard.hasGlobal(actor, authority)) {
                pluginBootstrap.add(authority);
            }
        }
        if (pluginBootstrap.isEmpty()) return;
        if (isPreview(actor) || !guard.hasGlobal(actor, "platform:modules:install"))
            fail(HttpStatus.FORBIDDEN, "forbidden");
        for (String authority : pluginBootstrap)
            audit.append(actor, "role.pluginPermission.bootstrap:" + authority, targetRoleId);
    }

    private void requireCurrentDelegation(String actor, Collection<String> authorities) {
        Set<String> active = permissions();
        for (String authority : new TreeSet<>(authorities))
            if (active.contains(authority)) guard.requireGlobal(actor, authority);
    }

    private void requireCurrentDelegation(String actor, RoleRepository.Role role) {
        requireCurrentDelegation(actor, role.authorities());
    }

    private void requireUpdateDelegation(
            String actor, UUID targetRoleId, Collection<String> previous, Collection<String> next) {
        var existing = new HashSet<>(previous);
        requireCurrentDelegation(actor, existing);
        var additions = new HashSet<>(next);
        additions.removeAll(existing);
        requireDelegation(actor, targetRoleId, additions);
    }

    private static int scopeRank(String scope) {
        return switch (scope) {
            case "SELF" -> 0;
            case "ORGANIZATION" -> 1;
            case "SUBTREE" -> 2;
            case "ALL" -> 3;
            default -> throw new IllegalArgumentException("Unknown data scope");
        };
    }

    private static boolean isPreview(String actor) {
        var snapshot = AuthenticationRequestContext.current();
        return snapshot != null
                && actor.equals(snapshot.principalName())
                && snapshot.authorization() != null
                && snapshot.authorization().previewActive();
    }

    public List<PermissionCatalogGroup> permissionCatalog() {
        return permissionCatalog(null, false);
    }

    public List<PermissionCatalogGroup> permissionCatalog(String actor) {
        guard.requirePageGlobal(actor, PlatformPageId.ROLES);
        return permissionCatalog(actor, true);
    }

    public List<String> permissionCodes(String actor) {
        guard.requirePageGlobal(actor, PlatformPageId.ROLES);
        return permissions().stream().sorted().toList();
    }

    private List<PermissionCatalogGroup> permissionCatalog(
            String actor, boolean includeEditability) {
        var catalog = new ArrayList<PermissionCatalogGroup>();
        for (var group : PlatformPermissionCatalog.groups()) {
            catalog.add(
                    new PermissionCatalogGroup(
                            group.id(),
                            group.title(),
                            "platform",
                            group.pages().stream().map(RoleService::catalogPage).toList(),
                            group.permissions().stream()
                                    .map(
                                            permission ->
                                                    new PermissionCatalogPermission(
                                                            permission.code(),
                                                            permission.label(),
                                                            List.of(),
                                                            editable(
                                                                    actor,
                                                                    permission.code(),
                                                                    "platform",
                                                                    includeEditability),
                                                            false))
                                    .toList(),
                            PlatformPermissionCatalog.VERSION));
        }
        Set<String> activePluginPermissions = pluginPermissions();
        for (var group : com.lumira.platform.modules.PluginPermissions.catalogGroups()) {
            var groupPermissions =
                    group.permissions().stream()
                            .filter(
                                    permission ->
                                            activePluginPermissions.contains(permission.code()))
                            .map(
                                    permission ->
                                            new PermissionCatalogPermission(
                                                    permission.code(),
                                                    permission.label(),
                                                    permission.requires(),
                                                    editable(
                                                            actor,
                                                            permission.code(),
                                                            "plugin",
                                                            includeEditability),
                                                    false))
                            .toList();
            if (groupPermissions.isEmpty()) continue;
            catalog.add(
                    new PermissionCatalogGroup(
                            group.id(),
                            group.title(),
                            "plugin",
                            group.pages().stream()
                                    .map(page -> catalogPage(page, activePluginPermissions))
                                    .toList(),
                            groupPermissions,
                            PlatformPermissionCatalog.VERSION));
        }
        var active = permissions();
        var retired =
                roles.permissionHistory().stream()
                        .filter(authority -> !active.contains(authority))
                        .distinct()
                        .sorted()
                        .map(
                                authority ->
                                        new PermissionCatalogPermission(
                                                authority, authority, List.of(), false, true))
                        .toList();
        if (!retired.isEmpty())
            catalog.add(
                    new PermissionCatalogGroup(
                            "retired-permissions",
                            "permissionRetired",
                            "platform",
                            List.of(),
                            retired,
                            PlatformPermissionCatalog.VERSION));
        return catalog.stream().sorted(Comparator.comparing(PermissionCatalogGroup::id)).toList();
    }

    private static PermissionCatalogPage catalogPage(PlatformPermissionCatalog.Page page) {
        return new PermissionCatalogPage(
                page.id(),
                page.path(),
                page.title(),
                page.navigationGroup(),
                page.navigationOrder(),
                page.accessPermission(),
                page.operationPermissions(),
                page.accessScope(),
                page.operationScopes(),
                "MAPPED");
    }

    private static PermissionCatalogPage catalogPage(
            com.lumira.platform.modules.PluginPermissions.Page page,
            Set<String> activePluginPermissions) {
        boolean accessIsDeclared =
                page.accessPermission() != null
                        && activePluginPermissions.contains(page.accessPermission());
        String access = accessIsDeclared ? page.accessPermission() : null;
        List<String> operations =
                accessIsDeclared
                        ? page.operationPermissions().stream()
                                .filter(activePluginPermissions::contains)
                                .sorted()
                                .toList()
                        : List.of();
        Map<String, String> operationScopes =
                operations.stream()
                        .collect(
                                java.util.stream.Collectors.toUnmodifiableMap(
                                        permission -> permission,
                                        permission ->
                                                page.operationScopes()
                                                        .getOrDefault(permission, "global")));
        String group =
                page.navigationGroup() == null || page.navigationGroup().isBlank()
                        ? "unassigned"
                        : page.navigationGroup();
        String status = access == null || "unassigned".equals(group) ? "UNASSIGNED" : "MAPPED";
        return new PermissionCatalogPage(
                page.id() == null || page.id().isBlank() ? pageIdFromPath(page.path()) : page.id(),
                page.path(),
                page.title(),
                group,
                page.navigationOrder(),
                access,
                operations,
                page.accessScope(),
                operationScopes,
                status);
    }

    private boolean editable(
            String actor, String permission, String source, boolean includeEditability) {
        if (!includeEditability || actor == null || isPreview(actor)) return false;
        return guard.hasGlobal(actor, permission)
                || ("plugin".equals(source) && guard.hasGlobal(actor, "platform:modules:install"));
    }

    private boolean hasPage(String actor, PlatformPageId page) {
        try {
            guard.requirePage(actor, page);
            return true;
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.FORBIDDEN) return false;
            throw exception;
        }
    }

    private boolean hasOperation(String actor, PlatformOperation operation) {
        try {
            guard.requireOperation(actor, operation);
            return true;
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.FORBIDDEN) return false;
            throw exception;
        }
    }

    private boolean hasUserOperation(String actor, UUID userId, PlatformOperation operation) {
        try {
            guard.requireOperationUser(actor, userId, operation);
            return true;
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.FORBIDDEN) return false;
            throw exception;
        }
    }

    private Set<String> globalAuthorities(String actor) {
        var snapshot = AuthenticationRequestContext.current();
        if (snapshot != null
                && actor.equals(snapshot.principalName())
                && snapshot.authorization() != null)
            return snapshot.authorization().effectiveGlobalAuthorities();
        return roles.actorGlobalAuthorities(actor);
    }

    private static String pageIdFromPath(String path) {
        if (path == null || path.isBlank()) return "";
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
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
        validateAssignableAuthorities(in.authorities(), Set.of());
        if (in.code() == null || !in.code().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}"))
            fail(HttpStatus.BAD_REQUEST, "invalidRole");
        begin(actor, PlatformOperation.ROLE_CREATE);
        var id = UUID.randomUUID();
        requireDelegation(actor, id, in.authorities());
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
        begin(actor, PlatformOperation.ROLE_UPDATE);
        lockRegistrationPolicyMutation();
        if (jdbc != null)
            jdbc.sql("SELECT id FROM platform_role WHERE id=:id FOR UPDATE")
                    .param("id", id)
                    .query(UUID.class)
                    .optional();
        var old = roles.find(id);
        if (in.version() == null || old.version() != in.version())
            fail(HttpStatus.CONFLICT, "staleRecord");
        validateAssignableAuthorities(in.authorities(), old.authorities());
        String requestedScope = in.dataScope() == null ? old.dataScope() : in.dataScope();
        boolean reenabled = !old.enabled() && Boolean.TRUE.equals(in.enabled());
        boolean scopeWidened = scopeRank(requestedScope) > scopeRank(old.dataScope());
        boolean authoritiesChanged = !new HashSet<>(old.authorities()).equals(in.authorities());
        if (authoritiesChanged || reenabled || scopeWidened) {
            Set<String> active = permissions();
            if ((reenabled || scopeWidened)
                    && old.authorities().stream()
                            .anyMatch(
                                    authority ->
                                            in.authorities().contains(authority)
                                                    && !active.contains(authority)))
                fail(HttpStatus.FORBIDDEN, "forbidden");
            if (authoritiesChanged || reenabled || scopeWidened)
                requireUpdateDelegation(actor, id, old.authorities(), in.authorities());
        }
        if (isDefaultRegistrationRole(id)
                && (!Boolean.TRUE.equals(in.enabled())
                        || !"SELF".equals(in.dataScope() == null ? old.dataScope() : in.dataScope())
                        || !RegistrationRoleAdapter.allowsAuthorities(in.authorities())))
            fail(HttpStatus.CONFLICT, "registrationRoleInUse");
        roles.update(
                id,
                in.name().strip(),
                in.enabled(),
                requestedScope,
                in.fileQuotaBytesLimit(),
                in.fileQuotaFilesLimit());
        if (authoritiesChanged) roles.authorities(id, in.authorities());
        if (Boolean.TRUE.equals(in.enabled())) validateAssignedPoliciesForRole(id);
        protect();
        audit.append(actor, "role.update", id);
        return roles.find(id);
    }

    @Transactional
    public void delete(UUID id, long version, String actor) {
        lockPolicyMutation();
        begin(actor, PlatformOperation.ROLE_DELETE);
        lockRegistrationPolicyMutation();
        if (jdbc != null)
            jdbc.sql("SELECT id FROM platform_role WHERE id=:id FOR UPDATE")
                    .param("id", id)
                    .query(UUID.class)
                    .optional();
        var role = roles.find(id);
        if (role.version() != version) fail(HttpStatus.CONFLICT, "staleRecord");
        if (isDefaultRegistrationRole(id)) fail(HttpStatus.CONFLICT, "registrationRoleInUse");
        if (roles.assigned(id)) fail(HttpStatus.CONFLICT, "roleInUse");
        requireCurrentDelegation(actor, role);
        roles.delete(id);
        protect();
        audit.append(actor, "role.delete", id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserRoles userRoles(UUID id, String actor) {
        try {
            guard.requireOperationUser(actor, id, PlatformOperation.USER_READ_ROLES);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() != HttpStatus.FORBIDDEN) throw exception;
            guard.requireOperationUser(actor, id, PlatformOperation.USER_ASSIGN_ROLES);
        }
        return new UserRoles(users.versionSnapshot(id), roles.userRoles(id));
    }

    @Transactional
    public UserRoles assign(UUID id, Assignment in, String actor) {
        if (in.roleIds() == null
                || in.roleIds().size() > 100
                || in.roleIds().stream().anyMatch(Objects::isNull))
            fail(HttpStatus.BAD_REQUEST, "invalidRole");
        lockPolicyMutation();
        guard.requireOperationUser(actor, id, PlatformOperation.USER_ASSIGN_ROLES);
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
        var additions = new HashSet<>(in.roleIds());
        var currentRoleIds = new HashSet<>(roles.userRoles(id));
        additions.removeAll(currentRoleIds);
        for (var roleId : additions) {
            var role = roles.find(roleId);
            validateAssignableAuthorities(role.authorities(), Set.of());
            requireDelegation(actor, roleId, role.authorities());
        }
        var removals = new HashSet<>(currentRoleIds);
        removals.removeAll(in.roleIds());
        for (var roleId : removals) requireCurrentDelegation(actor, roles.find(roleId));
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
