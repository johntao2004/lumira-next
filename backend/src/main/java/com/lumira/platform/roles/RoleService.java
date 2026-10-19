/**
 * 编排角色维护和分配，校验委派及撤权上限、版本与恢复管理员保护，防止角色变更扩大权限或破坏认证可达性。 Orchestrates role maintenance and assignment
 * with delegation and revocation ceilings, version checks and recovery-administrator protection,
 * preventing privilege expansion or loss of authentication reachability.
 */
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

        /**
         * 兼容缺少路由到权限映射的目录项。 Compatibility fallback for catalog entries without a route-to-permission
         * mapping.
         */
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
            String code,
            String label,
            List<String> requires,
            boolean editable,
            boolean retired,
            boolean registrationEligible) {
        public PermissionCatalogPermission {
            requires = List.copyOf(requires == null ? List.of() : requires);
        }

        public PermissionCatalogPermission(String code, String label) {
            this(code, label, List.of(), false, false, false);
        }

        public PermissionCatalogPermission(String code, String label, List<String> requires) {
            this(code, label, requires, false, false, false);
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
     * 最后一个字段仅作为请求兼容哨兵，只接受 null 或空集合；拒绝非空值，避免旧客户端请求遗留投影行为。
     *
     * <p>The final component is a request-only compatibility sentinel. It accepts only null or an
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
        Set<String> activeAuthorities = Set.of();
        RoleRepository.AuthorityScopeCeiling scopeCeiling =
                RoleRepository.AuthorityScopeCeiling.none();
        if (canAssign) {
            var catalog = AuthorityCatalog.capture();
            delegableAuthorities =
                    delegableAuthorities(
                            globalAuthorities(actor), effectiveAuthorities(actor), catalog);
            activeAuthorities = catalog.active();
            scopeCeiling = authorityScopeCeilings(actor, catalog, true);
        }

        return roles.assignmentOptions(
                        page,
                        Set.copyOf(roles.userRoles(userId)),
                        delegableAuthorities,
                        activeAuthorities,
                        scopeCeiling,
                        canAssign)
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

    /**
     * 一次变更只读取一次目录；后续卸载不能把新增权限移出委派检查，读取时已消失的权限也不能新增。
     *
     * <p>One catalog read for a mutation. Later uninstalls cannot drop an addition out of the
     * delegation check, and a permission that was already gone cannot be added.
     */
    static final class AuthorityCatalog {
        private final Set<String> host;
        private final Set<String> plugin;

        AuthorityCatalog(Set<String> host, Set<String> plugin) {
            this.host = Set.copyOf(host);
            this.plugin = Set.copyOf(plugin);
        }

        static AuthorityCatalog capture() {
            return new AuthorityCatalog(PERMISSIONS, pluginPermissions());
        }

        boolean active(String authority) {
            return host.contains(authority) || plugin.contains(authority);
        }

        Set<String> active() {
            var result = new LinkedHashSet<>(host);
            result.addAll(plugin);
            return Set.copyOf(result);
        }

        boolean host(String authority) {
            return host.contains(authority);
        }

        boolean plugin(String authority) {
            return plugin.contains(authority);
        }
    }

    private void validateAssignableAuthorities(
            Collection<String> requested, Collection<String> retained, AuthorityCatalog catalog) {
        var allowed = new HashSet<>(catalog.active());
        allowed.addAll(retained);
        if (!allowed.containsAll(requested))
            fail(HttpStatus.FORBIDDEN, "rolePermissionUnavailable");
    }

    /**
     * 配置角色的平台权限必须全局持有；向用户分配角色时，目录标为调用方范围的权限可按有效持有判断；初始化委派尚未持有的插件权限需安装权限及全部宿主权限，且只审计本次新增权限。
     *
     * <p>Platform grants on a role require global possession. Assigning a role to a user may use
     * effective possession for permissions the catalog marks as caller-scoped. Bootstrapping a
     * plugin permission the actor does not hold requires installation permission and every host
     * permission, and that bootstrap is audited only for permissions added by this request.
     */
    private void requireDelegation(
            String actor,
            UUID targetRoleId,
            Collection<String> authorities,
            Collection<String> additions,
            AuthorityCatalog catalog,
            boolean auditBootstrap,
            boolean assignment) {
        Set<String> added = Set.copyOf(additions);
        var pluginBootstrap = new TreeSet<String>();
        for (String authority : new TreeSet<>(authorities)) {
            if (!catalog.active(authority)) continue;
            if (catalog.host(authority) || !catalog.plugin(authority)) {
                if (PlatformOperation.MODULE_INSTALL.permission().equals(authority)
                        && !holdsEveryPlatformPermission(actor))
                    fail(HttpStatus.FORBIDDEN, "rolePermissionDelegationDenied");
                requireDelegable(actor, authority, false, !assignment || globallyScoped(authority));
            } else if (!holds(actor, authority, assignment)) {
                pluginBootstrap.add(authority);
            }
        }
        if (pluginBootstrap.isEmpty()) return;
        if (isPreview(actor)
                || !guard.hasGlobal(actor, PlatformOperation.MODULE_INSTALL.permission()))
            fail(HttpStatus.FORBIDDEN, "rolePermissionDelegationDenied");
        requireInstallAuthority(guard, actor);
        if (!auditBootstrap) return;
        for (String authority : pluginBootstrap)
            if (added.contains(authority))
                audit.append(actor, "role.pluginPermission.bootstrap:" + authority, targetRoleId);
    }

    private void requireCurrentDelegation(
            String actor, Collection<String> authorities, AuthorityCatalog catalog) {
        for (String authority : new TreeSet<>(authorities))
            if (catalog.active(authority)) requireDelegableGlobal(actor, authority, true);
    }

    private void requireDelegableGlobal(String actor, String authority, boolean revocation) {
        requireDelegable(actor, authority, revocation, true);
    }

    private void requireDelegable(
            String actor, String authority, boolean revocation, boolean globalOnly) {
        try {
            if (globalOnly) guard.requireGlobal(actor, authority);
            else guard.require(actor, authority);
        } catch (ResponseStatusException denied) {
            if (denied.getStatusCode().value() == 403 && "forbidden".equals(denied.getReason()))
                fail(
                        HttpStatus.FORBIDDEN,
                        revocation
                                ? "rolePermissionRevocationDenied"
                                : "rolePermissionDelegationDenied");
            throw denied;
        }
    }

    private boolean holds(String actor, String authority, boolean assignment) {
        if (guard.hasGlobal(actor, authority)) return true;
        if (!assignment || globallyScoped(authority)) return false;
        try {
            guard.require(actor, authority);
            return true;
        } catch (ResponseStatusException denied) {
            if (denied.getStatusCode().value() == 403 && "forbidden".equals(denied.getReason()))
                return false;
            throw denied;
        }
    }

    private void requireCurrentDelegation(
            String actor, RoleRepository.Role role, AuthorityCatalog catalog) {
        requireCurrentDelegation(actor, role.authorities(), catalog);
    }

    void requireUpdateDelegation(
            String actor,
            UUID targetRoleId,
            Collection<String> previous,
            Collection<String> next,
            AuthorityCatalog catalog) {
        // 该快照中的已有与新增权限均保留在检查中；不能二次读取目录而丢失请求开始时仍有效的新增权限。
        // Existing and added grants from this snapshot stay in the check. A second catalog read
        // must not drop an addition that was active when the request started.
        var additions = new LinkedHashSet<>(next);
        additions.removeAll(previous);
        for (String authority : additions)
            if (!catalog.active(authority)) fail(HttpStatus.FORBIDDEN, "rolePermissionUnavailable");
        var checked = new LinkedHashSet<>(previous);
        checked.addAll(next);
        checked.retainAll(catalog.active());
        requireDelegation(actor, targetRoleId, checked, additions, catalog, true, false);
    }

    /**
     * 全局权限仅在全局持有时可委派；调用方范围权限可按全局或有效授权持有判断，插件初始化委派仅限已持有全部宿主权限的操作者。
     *
     * <p>A global permission is delegable only when held globally. A caller-scoped permission is
     * delegable when held globally or in the actor's effective grants. Plugin bootstrap is limited
     * to an actor who already holds every host permission.
     */
    static Set<String> delegableAuthorities(
            Set<String> globalHeld, Set<String> effectiveHeld, AuthorityCatalog catalog) {
        boolean bootstrap =
                globalHeld.contains(PlatformOperation.MODULE_INSTALL.permission())
                        && globalHeld.containsAll(
                                com.lumira.platform.common.PlatformInstallAuthority
                                        .currentPlatformPermissions());
        var delegable = new LinkedHashSet<String>();
        for (String permission : catalog.active()) {
            if (catalog.plugin(permission) && bootstrap) {
                delegable.add(permission);
                continue;
            }
            boolean held =
                    globallyScoped(permission)
                            ? globalHeld.contains(permission)
                            : effectiveHeld.contains(permission) || globalHeld.contains(permission);
            if (held) delegable.add(permission);
        }
        return Set.copyOf(delegable);
    }

    static boolean globallyScoped(String permission) {
        for (PlatformPageId page : PlatformPageId.values())
            if (page.accessPermission().equals(permission))
                return page.scope() == PlatformOperation.Scope.GLOBAL;
        for (PlatformOperation operation : PlatformOperation.values())
            if (operation.permission().equals(permission))
                return operation.scope() == PlatformOperation.Scope.GLOBAL;
        for (var group : com.lumira.platform.modules.PluginPermissions.catalogGroups())
            for (var page : group.pages()) {
                if (permission.equals(page.accessPermission()))
                    return !"scoped".equals(page.accessScope());
                if (page.operationPermissions().contains(permission))
                    return !"scoped"
                            .equals(page.operationScopes().getOrDefault(permission, "global"));
            }
        return true;
    }

    private void requireInstallBundle(Collection<String> authorities) {
        if (!authorities.contains(PlatformOperation.MODULE_INSTALL.permission())) return;
        if (!authorities.containsAll(
                com.lumira.platform.common.PlatformInstallAuthority.currentPlatformPermissions()))
            fail(HttpStatus.CONFLICT, "moduleInstallRequiresPlatformAuthority");
    }

    /**
     * 执行安装或授予安装权限，都要求全局持有当前全部平台权限。 Installing or granting install requires every current platform
     * permission, held globally.
     */
    public static void requireInstallAuthority(
            com.lumira.platform.common.AdministrationPort guard, String actor) {
        com.lumira.platform.common.PlatformInstallAuthority.require(guard, actor);
    }

    /**
     * 预览导入中的角色分配，使用与 assign 相同的快照和委派规则，不写入初始化委派审计。
     *
     * <p>Preview of an import assignment. Uses the same snapshot and delegation rules as {@link
     * #assign} and does not write bootstrap audit events.
     */
    public void validateImportAssignment(UUID userId, Set<UUID> nextRoleIds, String actor) {
        if (nextRoleIds == null) return;
        var catalog = AuthorityCatalog.capture();
        Set<UUID> current = userId == null ? Set.of() : Set.copyOf(roles.userRoles(userId));
        var additions = new LinkedHashSet<>(nextRoleIds);
        additions.removeAll(current);
        for (UUID roleId : additions) {
            var role = roles.find(roleId);
            validateAssignableAuthorities(role.authorities(), role.authorities(), catalog);
            var active = role.authorities().stream().filter(catalog::active).toList();
            requireDelegation(actor, roleId, active, active, catalog, false, true);
            requireRoleScopeCovered(actor, role, active, catalog, false);
        }
        var removals = new LinkedHashSet<>(current);
        removals.removeAll(nextRoleIds);
        for (UUID roleId : removals) requireAssignmentRemoval(actor, roles.find(roleId), catalog);
    }

    /**
     * 分配与移除角色共用相同范围上限；操作者须以不小于角色的范围持有全部有效权限，对自己操作亦然，缺少范围信息绝不意味着可委派更大范围。
     *
     * <p>Assigning or removing a role uses the same scope ceiling. The actor must hold every active
     * permission at a data scope at least as wide as the role, including when the target user is
     * the actor. Missing scope information never implies permission to delegate a wider scope.
     */
    private void requireRoleScopeCovered(
            String actor,
            RoleRepository.Role role,
            Collection<String> activeAuthorities,
            AuthorityCatalog catalog,
            boolean revocation) {
        RoleRepository.AuthorityScopeCeiling ceiling =
                authorityScopeCeilings(actor, catalog, !revocation);
        int required = scopeRank(role.dataScope() == null ? "SELF" : role.dataScope());
        for (String authority : activeAuthorities)
            if (ceiling.rank(authority) < required)
                fail(
                        HttpStatus.FORBIDDEN,
                        revocation
                                ? "rolePermissionRevocationDenied"
                                : "rolePermissionDelegationDenied");
    }

    private void requireAssignmentRemoval(
            String actor, RoleRepository.Role role, AuthorityCatalog catalog) {
        var active = role.authorities().stream().filter(catalog::active).toList();
        for (String authority : new TreeSet<>(active))
            requireDelegable(actor, authority, true, globallyScoped(authority));
        requireRoleScopeCovered(actor, role, active, catalog, true);
    }

    private RoleRepository.AuthorityScopeCeiling authorityScopeCeilings(
            String actor, AuthorityCatalog catalog, boolean allowBootstrap) {
        Map<String, Set<String>> scopes = authorityScopes(actor);
        var self = new LinkedHashSet<String>();
        var organization = new LinkedHashSet<String>();
        var subtree = new LinkedHashSet<String>();
        var all = new LinkedHashSet<String>();
        var active = com.lumira.platform.modules.ActivePermissionCatalog.current();
        for (var entry : scopes.entrySet()) {
            if (entry.getKey() == null || !active.contains(entry.getKey())) continue;
            int rank = -1;
            for (String scope : entry.getValue()) {
                if (scope == null) continue;
                try {
                    rank = Math.max(rank, scopeRank(scope));
                } catch (IllegalArgumentException ignored) {
                    // 未知范围标签不会提高可委派上限。
                    // Unknown scope labels do not raise the ceiling.
                }
            }
            if (rank >= 0) self.add(entry.getKey());
            if (rank >= 1) organization.add(entry.getKey());
            if (rank >= 2) subtree.add(entry.getKey());
            if (rank >= 3) all.add(entry.getKey());
        }
        // 保留完整平台安装者首次委派插件权限的能力；已有范围授权仍受真实上限约束，撤回从不使用此例外。
        // Preserve first-time plugin delegation for the full platform installer. Existing
        // scoped grants retain their actual ceiling; revocation never uses this exception.
        if (allowBootstrap
                && !isPreview(actor)
                && guard.hasGlobal(actor, PlatformOperation.MODULE_INSTALL.permission())
                && holdsEveryPlatformPermission(actor)) {
            for (String authority : catalog.active()) {
                if (!catalog.plugin(authority)
                        || catalog.host(authority)
                        || self.contains(authority)
                        || holds(actor, authority, true)) continue;
                self.add(authority);
                organization.add(authority);
                subtree.add(authority);
                all.add(authority);
            }
        }
        return new RoleRepository.AuthorityScopeCeiling(self, organization, subtree, all);
    }

    private Map<String, Set<String>> authorityScopes(String actor) {
        var snapshot = AuthenticationRequestContext.current();
        if (snapshot != null
                && actor.equals(snapshot.principalName())
                && snapshot.authorization() != null)
            return snapshot.authorization().effectiveScopes();
        if (jdbc == null) {
            // 兼容构造器没有范围投影，只有独立验证的全局授权才能确定上限；仅有效持有并不足够，绝不能视为无限制委派。
            // The compatibility constructor has no scoped projection. Only independently
            // verified global grants provide a known ceiling; effective possession alone is
            // insufficient and must never be treated as unrestricted delegation.
            return globalAuthorities(actor).stream()
                    .collect(
                            java.util.stream.Collectors.toUnmodifiableMap(
                                    authority -> authority, authority -> Set.of("ALL")));
        }
        Map<String, Set<String>> values = new HashMap<>();
        jdbc.sql(
                        """
                        WITH actor AS (
                            SELECT id FROM platform_user
                             WHERE username=:name AND enabled AND deleted_at IS NULL
                        ), effective AS (
                            SELECT authority FROM platform_effective_authority e
                              JOIN actor ON e.user_id=actor.id
                        ), scope_sources AS (
                            SELECT a.authority, 'ALL'::text AS data_scope
                              FROM platform_authority a
                              JOIN actor ON a.user_id=actor.id
                              JOIN effective ON effective.authority=a.authority
                            UNION ALL
                            SELECT ra.authority, r.data_scope
                              FROM platform_user_role ur
                              JOIN actor ON ur.user_id=actor.id
                              JOIN platform_role r ON r.id=ur.role_id AND r.enabled
                              JOIN platform_role_authority ra ON ra.role_id=r.id
                              JOIN effective ON effective.authority=ra.authority
                        )
                        SELECT authority, data_scope FROM scope_sources
                        """)
                .param("name", actor)
                .query(
                        (rs, row) ->
                                Map.entry(rs.getString("authority"), rs.getString("data_scope")))
                .list()
                .forEach(
                        entry ->
                                values.computeIfAbsent(entry.getKey(), ignored -> new TreeSet<>())
                                        .add(entry.getValue()));
        return values;
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
                                                            false,
                                                            RegistrationRoleAdapter
                                                                    .allowsAuthorities(
                                                                            Set.of(
                                                                                    permission
                                                                                            .code()))))
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
                                                    false,
                                                    RegistrationRoleAdapter.allowsAuthorities(
                                                            Set.of(permission.code()))))
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
                                                authority, authority, List.of(), false, true,
                                                false))
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
        if (PlatformOperation.MODULE_INSTALL.permission().equals(permission))
            return holdsEveryPlatformPermission(actor);
        return guard.hasGlobal(actor, permission)
                || ("plugin".equals(source)
                        && !PERMISSIONS.contains(permission)
                        && guard.hasGlobal(actor, PlatformOperation.MODULE_INSTALL.permission()));
    }

    private boolean holdsEveryPlatformPermission(String actor) {
        for (String permission :
                com.lumira.platform.common.PlatformInstallAuthority.currentPlatformPermissions())
            if (!guard.hasGlobal(actor, permission)) return false;
        return true;
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

    private Set<String> effectiveAuthorities(String actor) {
        var snapshot = AuthenticationRequestContext.current();
        if (snapshot != null
                && actor.equals(snapshot.principalName())
                && snapshot.authorization() != null)
            return snapshot.authorization().effectiveAuthorities();
        return roles.actorEffectiveAuthorities(actor);
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
        if (in.code() == null || !in.code().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}"))
            fail(HttpStatus.BAD_REQUEST, "invalidRole");
        begin(actor, PlatformOperation.ROLE_CREATE);
        var catalog = AuthorityCatalog.capture();
        validateAssignableAuthorities(in.authorities(), Set.of(), catalog);
        requireInstallBundle(in.authorities());
        var id = UUID.randomUUID();
        requireDelegation(actor, id, in.authorities(), in.authorities(), catalog, true, false);
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
        // 认证策略锁必须先于 AdministrationGuard 的会话边界获取。
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
        var catalog = AuthorityCatalog.capture();
        validateAssignableAuthorities(in.authorities(), old.authorities(), catalog);
        requireInstallBundle(in.authorities());
        String requestedScope = in.dataScope() == null ? old.dataScope() : in.dataScope();
        boolean reenabled = !old.enabled() && Boolean.TRUE.equals(in.enabled());
        boolean scopeWidened = scopeRank(requestedScope) > scopeRank(old.dataScope());
        boolean authoritiesChanged = !new HashSet<>(old.authorities()).equals(in.authorities());
        if (isDefaultRegistrationRole(id)
                && (!Boolean.TRUE.equals(in.enabled())
                        || !"SELF".equals(requestedScope)
                        || !RegistrationRoleAdapter.allowsAuthorities(in.authorities())))
            fail(HttpStatus.CONFLICT, "registrationRoleInUse");
        if ((reenabled || scopeWidened)
                && old.authorities().stream()
                        .anyMatch(
                                authority ->
                                        in.authorities().contains(authority)
                                                && !catalog.active(authority)))
            fail(HttpStatus.CONFLICT, "roleRetainsRetiredPermissions");
        requireUpdateDelegation(actor, id, old.authorities(), in.authorities(), catalog);
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
        requireCurrentDelegation(actor, role, AuthorityCatalog.capture());
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
        var catalog = AuthorityCatalog.capture();
        var additions = new HashSet<>(in.roleIds());
        var currentRoleIds = new HashSet<>(roles.userRoles(id));
        additions.removeAll(currentRoleIds);
        for (var roleId : additions) {
            var role = roles.find(roleId);
            validateAssignableAuthorities(role.authorities(), role.authorities(), catalog);
            var active = role.authorities().stream().filter(catalog::active).toList();
            requireDelegation(actor, roleId, active, active, catalog, true, true);
            requireRoleScopeCovered(actor, role, active, catalog, false);
        }
        var removals = new HashSet<>(currentRoleIds);
        removals.removeAll(in.roleIds());
        for (var roleId : removals) requireAssignmentRemoval(actor, roles.find(roleId), catalog);
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
