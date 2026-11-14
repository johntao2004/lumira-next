/**
 * 维护会话级角色预览及授权修订，将实际权限与范围同目标角色取交集，并在请求和事务边界拒绝过期上下文及预览写入。 Maintains session role previews and
 * authorization revisions, intersecting actual grants and scopes with the target role and rejecting
 * stale contexts or preview writes at request and transaction boundaries.
 */
package com.lumira.platform.identity;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.common.PluginAuthorizationPort;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.AuthorizationContextBoundaryPort;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import com.lumira.platform.modules.ActivePermissionCatalog;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/** 基于共享数据库的只读角色预览会话上下文。 Shared-database session context for read-only role preview. */
@Service
public class RolePreviewContextService implements AuthorizationContextBoundaryPort {
    public static final String REVISION_HEADER = "X-Lumira-Authorization-Context-Revision";
    private static final int PREVIEW_MINUTES = 10;
    private static final String PREVIEW_PERMISSION = PlatformOperation.ROLE_PREVIEW.permission();
    private static final String ROLE_ACCESS_PERMISSION = PlatformPageId.ROLES.accessPermission();

    private static final class BoundaryValidationMarker
            implements org.springframework.transaction.support.TransactionSynchronization {
        private final String sessionPrimaryId;
        private final UUID actorId;

        BoundaryValidationMarker(String sessionPrimaryId, UUID actorId) {
            this.sessionPrimaryId = sessionPrimaryId;
            this.actorId = actorId;
        }

        boolean matches(String sessionPrimaryId, UUID actorId) {
            return Objects.equals(this.sessionPrimaryId, sessionPrimaryId)
                    && Objects.equals(this.actorId, actorId);
        }
    }

    public record View(String revision, boolean rolePreviewSupported, Preview preview) {}

    public record Preview(
            String state,
            UUID roleId,
            String roleName,
            long roleVersion,
            OffsetDateTime startedAt,
            OffsetDateTime expiresAt,
            boolean limitedByActor,
            String blockedReason) {}

    public record Candidate(
            UUID id,
            String code,
            String name,
            long version,
            String dataScope,
            boolean assigned,
            boolean limitedByActor) {}

    public record BootstrapResult(String username, boolean beforeGranted, boolean afterGranted) {}

    private record StoredContext(
            String sessionPrimaryId,
            UUID actorId,
            UUID revision,
            String state,
            UUID roleId,
            String roleCode,
            String roleName,
            Long roleVersion,
            String dataScope,
            OffsetDateTime startedAt,
            OffsetDateTime expiresAt,
            boolean limitedByActor,
            String blockedReason,
            String actorAuthorityDigest,
            String roleAuthorityDigest,
            Set<String> globalAuthorities) {
        StoredContext(
                String sessionPrimaryId,
                UUID actorId,
                UUID revision,
                String state,
                UUID roleId,
                String roleCode,
                String roleName,
                Long roleVersion,
                String dataScope,
                OffsetDateTime startedAt,
                OffsetDateTime expiresAt,
                boolean limitedByActor,
                String blockedReason,
                String actorAuthorityDigest,
                String roleAuthorityDigest) {
            this(
                    sessionPrimaryId,
                    actorId,
                    revision,
                    state,
                    roleId,
                    roleCode,
                    roleName,
                    roleVersion,
                    dataScope,
                    startedAt,
                    expiresAt,
                    limitedByActor,
                    blockedReason,
                    actorAuthorityDigest,
                    roleAuthorityDigest,
                    null);
        }
    }

    private record RoleSnapshot(
            UUID id,
            String code,
            String name,
            boolean enabled,
            long version,
            String dataScope,
            Set<String> authorities) {}

    private record ScopeSnapshot(Set<String> globalAuthorities, Map<String, Set<String>> scopes) {}

    private final JdbcClient jdbc;
    private final AuditPort audit;
    private final ObjectProvider<PluginAuthorizationPort> pluginAuthorizations;
    private final AuthenticationPolicyWriteLock policyLock;

    public RolePreviewContextService(JdbcClient jdbc, AuditPort audit) {
        this(jdbc, audit, null, null);
    }

    @Autowired
    public RolePreviewContextService(
            JdbcClient jdbc,
            AuditPort audit,
            ObjectProvider<PluginAuthorizationPort> pluginAuthorizations,
            AuthenticationPolicyWriteLock policyLock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.pluginAuthorizations = pluginAuthorizations;
        this.policyLock = policyLock;
    }

    static final class SchemaRelationState {
        private volatile Boolean effectiveAuthorityView;
        private volatile Boolean globalAuthorityView;
        private final Map<String, Boolean> relationCache =
                new java.util.concurrent.ConcurrentHashMap<>();

        boolean isEffectiveAuthorityView(JdbcClient jdbc) {
            Boolean cached = effectiveAuthorityView;
            if (cached != null) return cached;
            boolean isView = isViewRelation(jdbc, "platform_effective_authority");
            effectiveAuthorityView = isView;
            return isView;
        }

        boolean isGlobalAuthorityView(JdbcClient jdbc) {
            Boolean cached = globalAuthorityView;
            if (cached != null) return cached;
            boolean isView = isViewRelation(jdbc, "platform_global_authority");
            globalAuthorityView = isView;
            return isView;
        }

        private boolean isViewRelation(JdbcClient jdbc, String relationName) {
            return Boolean.TRUE.equals(
                    jdbc.sql(
                                    """
                                    SELECT c.relkind = 'v'
                                      FROM pg_class c
                                      JOIN pg_namespace n ON n.oid = c.relnamespace
                                     WHERE c.relname = :relname
                                       AND n.nspname = current_schema()
                                    """)
                            .param("relname", relationName)
                            .query(Boolean.class)
                            .optional()
                            .orElse(false));
        }

        boolean hasRelation(JdbcClient jdbc, String relationName) {
            return relationCache.computeIfAbsent(
                    relationName,
                    name ->
                            Boolean.TRUE.equals(
                                    jdbc.sql(
                                                    """
                                                    SELECT EXISTS (
                                                        SELECT 1 FROM pg_class c
                                                          JOIN pg_namespace n ON n.oid = c.relnamespace
                                                         WHERE c.relname = :relname
                                                           AND n.nspname = current_schema()
                                                    )
                                                    """)
                                            .param("relname", name)
                                            .query(Boolean.class)
                                            .optional()
                                            .orElse(false)));
        }
    }

    private final SchemaRelationState schemaRelations = new SchemaRelationState();

    private boolean isEffectiveAuthorityView() {
        return schemaRelations.isEffectiveAuthorityView(jdbc);
    }

    private boolean isGlobalAuthorityView() {
        return schemaRelations.isGlobalAuthorityView(jdbc);
    }

    boolean hasRelation(String relationName) {
        return schemaRelations.hasRelation(jdbc, relationName);
    }

    /**
     * 由 CurrentAuthorityFilter 在真实认证与 MFA 校验通过后调用。 Called by CurrentAuthorityFilter after real
     * authentication and MFA are verified.
     */
    @Transactional
    public RequestAuthorizationContext resolve(
            HttpServletRequest request,
            String principalName,
            UUID actorId,
            Set<String> currentRealAuthorities) {
        return resolve(request, principalName, actorId, currentRealAuthorities, null);
    }

    @Transactional
    public RequestAuthorizationContext resolve(
            HttpServletRequest request,
            String principalName,
            UUID actorId,
            Set<String> currentRealAuthorities,
            Set<String> currentRealGlobalAuthorities) {
        StoredContext stored =
                ensureAndLockContext(
                        request, principalName, actorId, currentRealGlobalAuthorities != null);
        String sessionPrimaryId = stored.sessionPrimaryId();
        Set<String> realAuthorities = ActivePermissionCatalog.retain(currentRealAuthorities);
        // 普通请求同样需要真实的逐权限范围投影以校验委派；未启用预览不能抹去已知范围，也不能代表无限制访问。
        // Ordinary requests also need the real per-permission scope projection for delegation.
        // An absent preview must not erase known scopes or imply unrestricted access.
        ScopeSnapshot scopeSnapshot = scopeSnapshot(actorId);
        Set<String> realGlobal = scopeSnapshot.globalAuthorities();
        Map<String, Set<String>> realScopes = scopeSnapshot.scopes();

        if ("ACTIVE".equals(stored.state())) {
            String actorDigest = authorityDigest(actorId, realAuthorities, realGlobal, realScopes);
            String invalidReason = invalidPreviewReason(stored, actorDigest, actorId, realGlobal);
            if (invalidReason != null) {
                block(stored, invalidReason);
                stored = lockContext(sessionPrimaryId, actorId);
                audit.append(principalName, "role-preview.blocked", stored.roleId());
            }
        }

        RoleSnapshot target = "ACTIVE".equals(stored.state()) ? role(stored.roleId(), false) : null;
        Set<String> effectiveAuthorities = realAuthorities;
        Set<String> effectiveGlobal = realGlobal;
        Map<String, Set<String>> effectiveScopes = realScopes;
        if ("BLOCKED".equals(stored.state())) {
            effectiveAuthorities = Set.of();
            effectiveGlobal = Set.of();
            effectiveScopes = Map.of();
        } else if (target != null) {
            var intersection = new HashSet<>(realAuthorities);
            intersection.retainAll(target.authorities());
            effectiveAuthorities = Set.copyOf(intersection);
            if ("ALL".equals(target.dataScope())) {
                var globalIntersection = new HashSet<>(realGlobal);
                globalIntersection.retainAll(target.authorities());
                effectiveGlobal = Set.copyOf(globalIntersection);
            } else {
                effectiveGlobal = Set.of();
            }
            effectiveScopes = intersectScopes(realScopes, intersection, target.dataScope());
        }

        RequestAuthorizationContext authorization =
                new RequestAuthorizationContext(
                        principalName,
                        actorId,
                        stored.sessionPrimaryId(),
                        realAuthorities,
                        realGlobal,
                        realScopes,
                        effectiveAuthorities,
                        effectiveGlobal,
                        effectiveScopes,
                        stored.revision().toString(),
                        request.getHeader(REVISION_HEADER),
                        true,
                        authorizationPreview(stored));
        return authorization;
    }

    RequestAuthorizationContext resolvePreloadedNoneContext(
            HttpServletRequest request,
            String principalName,
            UUID actorId,
            String sessionPrimaryId,
            UUID revision,
            Set<String> currentRealAuthorities,
            Set<String> currentRealGlobalAuthorities) {
        Set<String> realAuthorities = ActivePermissionCatalog.retain(currentRealAuthorities);
        Set<String> realGlobal = ActivePermissionCatalog.retain(currentRealGlobalAuthorities);
        return new RequestAuthorizationContext(
                principalName,
                actorId,
                sessionPrimaryId,
                realAuthorities,
                realGlobal,
                Map.of(),
                realAuthorities,
                realGlobal,
                Map.of(),
                revision.toString(),
                request.getHeader(REVISION_HEADER),
                true,
                null);
    }

    /**
     * 在上下文行提交后执行，使 BLOCKED 状态转换不会随被拒绝请求回滚。 Run after the context row has committed so BLOCKED
     * transitions survive rejected requests.
     */
    public void enforceRequestBoundary(
            HttpServletRequest request, RequestAuthorizationContext context) {
        enforceRequestBoundaryInternal(request, context);
    }

    @Transactional
    public void recordBoundaryRejection(RequestAuthorizationContext context, String reason) {
        if (context == null || (!context.previewActive() && !context.previewBlocked())) return;
        String code = "rolePreviewReadOnly".equals(reason) ? "readOnly" : "blocked";
        UUID target = context.targetRoleId() == null ? context.actorId() : context.targetRoleId();
        audit.append(context.principalName(), "role-preview.denied." + code, target);
    }

    @Transactional(readOnly = true)
    public PageResult<Candidate> candidates(HttpServletRequest request, PageRequest page) {
        RequestAuthorizationContext context = authorization(request);
        boolean mayPreviewUnassigned =
                context.realGlobalAuthorities().contains(PREVIEW_PERMISSION)
                        && context.realGlobalAuthorities().contains(ROLE_ACCESS_PERMISSION);
        Map<String, Set<String>> realScopes =
                context.realScopes().isEmpty()
                        ? scopeSnapshot(context.actorId()).scopes()
                        : context.realScopes();
        String search = page.search();
        long total =
                jdbc.sql(
                                """
                        SELECT count(*)
                          FROM platform_role r
                         WHERE r.enabled
                           AND (:mayPreviewUnassigned OR EXISTS (
                               SELECT 1 FROM platform_user_role ur
                                WHERE ur.user_id=:actor AND ur.role_id=r.id))
                           AND (:search='' OR strpos(lower(r.code),lower(:search))>0
                                OR strpos(lower(r.name),lower(:search))>0)
                        """)
                        .param("mayPreviewUnassigned", mayPreviewUnassigned)
                        .param("actor", context.actorId())
                        .param("search", search)
                        .query(Long.class)
                        .single();
        List<Candidate> items =
                jdbc.sql(
                                """
                        SELECT r.id,r.code,r.name,r.version,r.data_scope,
                               EXISTS(SELECT 1 FROM platform_user_role ur
                                       WHERE ur.user_id=:actor AND ur.role_id=r.id) AS assigned,
                               coalesce(string_agg(a.authority,chr(31) ORDER BY a.authority),'') AS authorities
                          FROM platform_role r
                          LEFT JOIN LATERAL (
                               SELECT authority FROM platform_role_authority
                                WHERE role_id=r.id
                                  AND authority NOT LIKE 'platform:__v50:%'
                          ) a ON true
                         WHERE r.enabled
                           AND (:mayPreviewUnassigned OR EXISTS (
                               SELECT 1 FROM platform_user_role ur
                                WHERE ur.user_id=:actor AND ur.role_id=r.id))
                           AND (:search='' OR strpos(lower(r.code),lower(:search))>0
                                OR strpos(lower(r.name),lower(:search))>0)
                         GROUP BY r.id
                         ORDER BY r.code,r.id
                         LIMIT :limit OFFSET :offset
                        """)
                        .param("mayPreviewUnassigned", mayPreviewUnassigned)
                        .param("actor", context.actorId())
                        .param("search", search)
                        .param("limit", page.pageSize())
                        .param("offset", page.offset())
                        .query(
                                (rs, row) -> {
                                    Set<String> targetAuthorities =
                                            splitAuthorities(rs.getString("authorities"));
                                    return new Candidate(
                                            rs.getObject("id", UUID.class),
                                            rs.getString("code"),
                                            rs.getString("name"),
                                            rs.getLong("version"),
                                            rs.getString("data_scope"),
                                            rs.getBoolean("assigned"),
                                            limitedByActor(
                                                    context.realAuthorities(),
                                                    context.realGlobalAuthorities(),
                                                    realScopes,
                                                    targetAuthorities,
                                                    rs.getString("data_scope")));
                                })
                        .list();
        return new PageResult<>(items, total, page.page(), page.pageSize());
    }

    @Transactional
    public View enter(
            HttpServletRequest request, UUID roleId, Long roleVersion, String expectedRevision) {
        if (policyLock != null) policyLock.lockForAuthentication();
        RequestAuthorizationContext context = authorization(request);
        StoredContext stored = lockContext(context.sessionPrimaryId(), context.actorId());
        requireRevision(stored, expectedRevision);
        Set<String> realGlobal = globalAuthorities(context.actorId());
        Set<String> realAuthorities = effectiveAuthorities(context.actorId());
        Map<String, Set<String>> realScopes = scopeSnapshot(context.actorId()).scopes();
        String actorDigest =
                authorityDigest(context.actorId(), realAuthorities, realGlobal, realScopes);
        if ("ACTIVE".equals(stored.state())
                && !Objects.equals(stored.actorAuthorityDigest(), actorDigest)) {
            block(stored, "ACTOR_AUTHORITY_CHANGED");
            throw changed();
        }
        RoleSnapshot target = role(roleId, true);
        if (target == null || !target.enabled())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (roleVersion == null || target.version() != roleVersion)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        boolean assigned = assigned(context.actorId(), roleId);
        boolean administratorPreview =
                realGlobal.contains(PREVIEW_PERMISSION)
                        && realGlobal.contains(ROLE_ACCESS_PERMISSION);
        if (!assigned && !administratorPreview)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        OffsetDateTime now = OffsetDateTime.now();
        UUID nextRevision = UUID.randomUUID();
        String roleDigest = digest(target.authorities());
        jdbc.sql(
                        """
                        UPDATE platform_role_preview_context
                           SET revision=:revision,state='ACTIVE',role_id=:roleId,role_code=:code,
                               role_name=:name,role_version=:roleVersion,data_scope=:dataScope,
                               started_at=:startedAt,expires_at=:expiresAt,limited_by_actor=:limited,
                               blocked_reason=NULL,actor_authority_digest=:actorDigest,
                               role_authority_digest=:roleDigest,updated_at=CURRENT_TIMESTAMP
                         WHERE session_primary_id=CAST(:session AS character(36)) AND actor_id=:actor
                        """)
                .param("revision", nextRevision)
                .param("roleId", target.id())
                .param("code", target.code())
                .param("name", target.name())
                .param("roleVersion", target.version())
                .param("dataScope", target.dataScope())
                .param("startedAt", now)
                .param("expiresAt", now.plusMinutes(PREVIEW_MINUTES))
                .param(
                        "limited",
                        limitedByActor(
                                realAuthorities,
                                realGlobal,
                                realScopes,
                                target.authorities(),
                                target.dataScope()))
                .param("actorDigest", actorDigest)
                .param("roleDigest", roleDigest)
                .param("session", stored.sessionPrimaryId())
                .param("actor", context.actorId())
                .update();
        audit.append(context.principalName(), "role-preview.enter", roleId);
        StoredContext updated = lockContext(stored.sessionPrimaryId(), context.actorId());
        return view(updated);
    }

    @Transactional
    public View exit(HttpServletRequest request, String expectedRevision) {
        if (policyLock != null) policyLock.lockForAuthentication();
        RequestAuthorizationContext context = authorization(request);
        StoredContext stored = lockContext(context.sessionPrimaryId(), context.actorId());
        requireRevision(stored, expectedRevision);
        UUID targetId = stored.roleId() == null ? context.actorId() : stored.roleId();
        jdbc.sql(
                        """
                        UPDATE platform_role_preview_context
                           SET revision=:revision,state='NONE',role_id=NULL,role_code=NULL,role_name=NULL,
                               role_version=NULL,data_scope=NULL,started_at=NULL,expires_at=NULL,
                               limited_by_actor=false,blocked_reason=NULL,actor_authority_digest=NULL,
                               role_authority_digest=NULL,updated_at=CURRENT_TIMESTAMP
                         WHERE session_primary_id=CAST(:session AS character(36)) AND actor_id=:actor
                        """)
                .param("revision", UUID.randomUUID())
                .param("session", stored.sessionPrimaryId())
                .param("actor", context.actorId())
                .update();
        audit.append(context.principalName(), "role-preview.exit", targetId);
        return view(lockContext(stored.sessionPrimaryId(), context.actorId()));
    }

    @Transactional
    public BootstrapResult bootstrap(
            HttpServletRequest request, String username, String operation, String reason) {
        if (policyLock != null) policyLock.lockForAuthentication();
        RequestAuthorizationContext context = authorization(request);
        requireRevision(
                lockContext(context.sessionPrimaryId(), context.actorId()), context.revision());
        String cleanUsername = username == null ? "" : username.strip();
        String cleanReason = reason == null ? "" : reason.strip();
        String action =
                operation == null ? "" : operation.strip().toUpperCase(java.util.Locale.ROOT);
        if (!cleanUsername.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}")
                || cleanReason.length() < 8
                || cleanReason.length() > 500
                || !("GRANT".equals(action) || "REVOKE".equals(action)))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalidRolePreviewBootstrap");
        requireGlobalBootstrapOperator(context.actorId());
        UUID targetId =
                jdbc.sql(
                                "SELECT id FROM platform_user WHERE username=:username AND enabled AND deleted_at IS NULL")
                        .param("username", cleanUsername)
                        .query(UUID.class)
                        .optional()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireGlobalBootstrapOperator(targetId);
        boolean before = hasDirectPreviewGrant(targetId);
        if ("GRANT".equals(action)) {
            jdbc.sql(
                            "INSERT INTO platform_authority(user_id,authority) VALUES(:user,:permission) ON CONFLICT DO NOTHING")
                    .param("user", targetId)
                    .param("permission", PREVIEW_PERMISSION)
                    .update();
        } else {
            jdbc.sql("DELETE FROM platform_authority WHERE user_id=:user AND authority=:permission")
                    .param("user", targetId)
                    .param("permission", PREVIEW_PERMISSION)
                    .update();
        }
        boolean after = hasDirectPreviewGrant(targetId);
        jdbc.sql(
                        """
                        INSERT INTO platform_role_preview_bootstrap_audit(
                            event_id,actor_id,target_user_id,operation,reason,before_granted,after_granted)
                        VALUES(:event,:actor,:target,:operation,:reason,:before,:after)
                        """)
                .param("event", UUID.randomUUID())
                .param("actor", context.actorId())
                .param("target", targetId)
                .param("operation", action)
                .param("reason", cleanReason)
                .param("before", before)
                .param("after", after)
                .update();
        audit.append(
                context.principalName(),
                "role-preview.bootstrap." + action.toLowerCase(java.util.Locale.ROOT),
                targetId);
        return new BootstrapResult(cleanUsername, before, after);
    }

    public View view(HttpServletRequest request) {
        return view(authorization(request));
    }

    /**
     * 由受保护服务操作与分段写事务调用；会话共享锁一直持有到业务事务结束，避免进入或退出预览超越已跨过写边界的请求。
     *
     * <p>Called from guarded service operations and split write transactions. The per-session share
     * lock remains held until the business transaction completes, so enter/exit cannot overtake a
     * request that already crossed its write boundary.
     */
    @Override
    public void lockAndValidateCurrentRequest() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) return;
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attrs)) return;
        HttpServletRequest request = attrs.getRequest();
        AuthenticationRequestContext.Snapshot snapshot = AuthenticationRequestContext.current();
        if (snapshot == null || snapshot.authorization() == null) return;
        RequestAuthorizationContext context = snapshot.authorization();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        if (isContextControl(path, method)) return;

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
                if (sync instanceof BoundaryValidationMarker marker
                        && marker.matches(context.sessionPrimaryId(), context.actorId())) {
                    return;
                }
            }
        }

        boolean writeBoundary = !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        // 写边界保持先策略、后会话的统一锁顺序；PostgreSQL 只读事务不能执行加锁 SELECT，因此无锁重读相同实时行，再执行同样的修订、状态、过期及摘要检查。
        // Write boundaries must retain the shared lock order: policy, then session. Read-only
        // transactions cannot issue locking SELECTs in PostgreSQL, so they re-read the same live
        // rows without locks and run the identical revision/state/expiry/digest checks below.
        StoredContext current;
        if (writeBoundary && policyLock instanceof IdentityAuthenticationPolicyWriteLock) {
            current = lockPolicyAndContextShared(context.sessionPrimaryId(), context.actorId());
        } else {
            if (writeBoundary && policyLock != null) policyLock.lockForAuthentication();
            current =
                    readOrLockContextShared(
                            context.sessionPrimaryId(), context.actorId(), writeBoundary);
        }
        boolean staticPluginAsset = "GET".equals(method) && safeFrontendAsset(path);
        String suppliedRevision =
                staticPluginAsset
                        ? request.getParameter("authorizationContextRevision")
                        : request.getHeader(REVISION_HEADER);
        if (!Objects.equals(current.revision().toString(), suppliedRevision)) throw changed();
        if (!Objects.equals(current.revision().toString(), context.revision())) throw changed();
        if ("BLOCKED".equals(current.state()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "rolePreviewBlocked");
        if ("ACTIVE".equals(current.state())) {
            if (current.expiresAt() == null || !current.expiresAt().isAfter(OffsetDateTime.now()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "rolePreviewBlocked");
            if (!safePreviewRead(path, method, context) && !isTicketIssue(path, method))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "rolePreviewReadOnly");
            Set<String> actorAuthorities = effectiveAuthorities(context.actorId());
            ScopeSnapshot actorScopes = scopeSnapshot(context.actorId());
            String actorDigest =
                    authorityDigest(
                            context.actorId(),
                            actorAuthorities,
                            actorScopes.globalAuthorities(),
                            actorScopes.scopes());
            if (!Objects.equals(current.actorAuthorityDigest(), actorDigest))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "rolePreviewBlocked");
            RoleSnapshot target = role(current.roleId(), writeBoundary);
            if (target == null
                    || !target.enabled()
                    || !Objects.equals(target.version(), current.roleVersion())
                    || !Objects.equals(digest(target.authorities()), current.roleAuthorityDigest()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "rolePreviewBlocked");
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new BoundaryValidationMarker(context.sessionPrimaryId(), context.actorId()));
        }
    }

    boolean canCombineReadOnlyNoneBoundary(RequestAuthorizationContext context) {
        if (context == null
                || context.previewActive()
                || context.previewBlocked()
                || context.sessionPrimaryId() == null
                || context.actorId() == null) {
            return false;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
            if (sync instanceof BoundaryValidationMarker marker
                    && marker.matches(context.sessionPrimaryId(), context.actorId())) {
                return false;
            }
        }
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attrs)) {
            return false;
        }
        HttpServletRequest request = attrs.getRequest();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        if (isContextControl(path, method)) {
            return false;
        }
        return !("GET".equals(method) && safeFrontendAsset(path));
    }

    boolean completeCombinedReadOnlyNoneBoundary(
            RequestAuthorizationContext context, String stateAndRevision) {
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attrs)) {
            return false;
        }
        String suppliedRevision = attrs.getRequest().getHeader(REVISION_HEADER);
        if (stateAndRevision == null
                || !stateAndRevision.equals("NONE:" + context.revision())
                || !Objects.equals(context.revision(), suppliedRevision)) {
            return false;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new BoundaryValidationMarker(context.sessionPrimaryId(), context.actorId()));
        }
        return true;
    }

    private static final String CONTEXT_COLUMNS =
            """
            trim(c.session_primary_id) AS session_primary_id,c.actor_id,c.revision,c.state,
                   c.role_id,c.role_code,c.role_name,c.role_version,c.data_scope,c.started_at,c.expires_at,
                   c.limited_by_actor,c.blocked_reason,c.actor_authority_digest,c.role_authority_digest
            """;

    private StoredContext queryStoredContext(String sql, String primaryId, UUID actorId) {
        return jdbc.sql(sql)
                .param("session", primaryId)
                .param("actor", actorId)
                .query(this::mapContext)
                .optional()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED, "authenticationRequired"));
    }

    private StoredContext readOrLockContextShared(String primaryId, UUID actorId, boolean lock) {
        return queryStoredContext(
                "SELECT "
                        + CONTEXT_COLUMNS
                        + """
                          FROM platform_role_preview_context c
                         WHERE c.session_primary_id=CAST(:session AS character(36)) AND c.actor_id=:actor
                        """
                        + (lock ? " FOR SHARE" : ""),
                primaryId,
                actorId);
    }

    private StoredContext lockPolicyAndContextShared(String primaryId, UUID actorId) {
        return queryStoredContext(
                """
                WITH policy_lock AS MATERIALIZED (
                    SELECT id FROM platform_authentication_policy_lock WHERE id=1 FOR SHARE
                )
                SELECT
                """
                        + CONTEXT_COLUMNS
                        + """
                          FROM policy_lock pl
                          JOIN platform_role_preview_context c
                            ON pl.id=1
                           AND c.session_primary_id=CAST(:session AS character(36))
                           AND c.actor_id=:actor
                           FOR SHARE OF c
                        """,
                primaryId,
                actorId);
    }

    private void enforceRequestBoundaryInternal(
            HttpServletRequest request, RequestAuthorizationContext context) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        PluginAuthorizationPort callbackAuthorization =
                pluginAuthorizations == null ? null : pluginAuthorizations.getIfAvailable();
        if (callbackAuthorization != null && callbackAuthorization.signedCallback(method, path))
            return;
        boolean control = isContextControl(path, method);
        boolean staticPluginAsset = "GET".equals(method) && safeFrontendAsset(path);
        String suppliedRevision =
                staticPluginAsset
                        ? request.getParameter("authorizationContextRevision")
                        : context.requestedRevision();
        if (!control && !Objects.equals(context.revision(), suppliedRevision)) throw changed();
        if (context.previewBlocked() && !control)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "rolePreviewBlocked");
        if (context.previewActive()
                && !safePreviewRead(path, method, context)
                && !control
                && !isTicketIssue(path, method))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "rolePreviewReadOnly");
    }

    private static boolean isTicketIssue(String path, String method) {
        return "/api/account/role-preview/resource-tickets".equals(path) && "POST".equals(method);
    }

    private static boolean isContextControl(String path, String method) {
        if ("/api/auth/me".equals(path)
                || "/api/auth/csrf".equals(path)
                || "/api/auth/logout".equals(path)) return true;
        return "/api/account/role-preview".equals(path)
                        && Set.of("GET", "POST", "DELETE").contains(method)
                || "/api/account/role-preview/roles".equals(path) && "GET".equals(method);
    }

    private boolean safePreviewRead(
            String path, String method, RequestAuthorizationContext context) {
        if (!"GET".equals(method)) return false;
        if (Set.of(
                        "/api/auth/me",
                        "/api/auth/csrf",
                        "/api/auth/methods",
                        "/api/platform/modules",
                        "/api/platform/navigation",
                        "/api/account/profile",
                        "/api/account/profile-values",
                        "/api/account/profile-fields",
                        "/api/account/avatar",
                        "/api/account/files",
                        "/api/account/files/usage",
                        "/api/account/notifications",
                        "/api/account/notifications/unread-count",
                        "/api/account/security-events",
                        "/api/account/authentication-policy",
                        "/api/account/role-preview",
                        "/api/account/role-preview/roles")
                .contains(path)) return true;
        if (path.matches(
                        "/api/account/profile-dictionaries/[A-Za-z][A-Za-z0-9_]{0,63}/items(?:/[0-9a-fA-F-]{36})?")
                || path.matches(
                        "/api/platform/users/[0-9a-fA-F-]{36}/profile-dictionaries/[A-Za-z][A-Za-z0-9_]{0,63}/items(?:/[0-9a-fA-F-]{36})?")
                || path.matches("/api/account/role-preview/resource-tickets/[0-9a-fA-F-]{36}")
                || path.matches("/api/account/files/[0-9a-fA-F-]{36}")
                || path.matches("/api/platform/users")
                || path.matches("/api/platform/users/[0-9a-fA-F-]{36}")
                || path.matches("/api/platform/users/[0-9a-fA-F-]{36}/profile-values")
                || path.matches("/api/platform/users/[0-9a-fA-F-]{36}/file-quota(?:/preview)?")
                || path.matches("/api/platform/organizations")
                || path.matches("/api/platform/organizations/[0-9a-fA-F-]{36}")
                || path.matches("/api/platform/organizations/[0-9a-fA-F-]{36}/ancestors")
                || path.matches("/api/platform/roles")
                || path.matches("/api/platform/roles/[0-9a-fA-F-]{36}")
                || "/api/platform/permissions".equals(path)
                || "/api/platform/modules/plugins/frontend".equals(path)) return true;
        if (safeFrontendAsset(path)) return true;
        PluginAuthorizationPort plugins =
                pluginAuthorizations == null ? null : pluginAuthorizations.getIfAvailable();
        return plugins != null
                && plugins.previewReadOnly(
                        method,
                        path,
                        context.effectiveAuthorities(),
                        context.effectiveGlobalAuthorities());
    }

    private boolean safeFrontendAsset(String path) {
        PluginAuthorizationPort plugins =
                pluginAuthorizations == null ? null : pluginAuthorizations.getIfAvailable();
        return plugins != null && plugins.safeFrontendAsset(path);
    }

    private StoredContext ensureAndLockContext(
            HttpServletRequest request, String principalName, UUID actorId) {
        return ensureAndLockContext(request, principalName, actorId, false);
    }

    private StoredContext ensureAndLockContext(
            HttpServletRequest request,
            String principalName,
            UUID actorId,
            boolean skipGlobalAuthoritiesQuery) {
        var session = request.getSession(false);
        if (session == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authenticationRequired");
        // 正常请求准入可并发执行，而状态转换仍需冲突的排他行锁；仅将共享读限制在 NONE 状态，以免并发 ACTIVE 读者升级为更新锁时因阻止无效预览而产生死锁。
        // Normal admissions can overlap, while transitions still require the conflicting exclusive
        // row lock. Restrict the shared read to NONE: upgrading concurrent ACTIVE readers to update
        // locks could deadlock when either reader needs to block an invalid preview.
        var normal =
                contextForAdmission(
                        session.getId(), principalName, actorId, true, !skipGlobalAuthoritiesQuery);
        if (normal.isPresent()) return normal.get();
        var existing = activeContextForUpdate(session.getId(), principalName, actorId);
        if (existing.isPresent()) return existing.get();

        // 常见 NONE 路径只需一次加锁读取；首次使用分开执行 INSERT 与 SELECT，因为同一语句快照看不到未被引用的数据修改 CTE 新插入的行，ON CONFLICT
        // 还会串行化同一会话的两个首次请求，让后续语句看到已提交的胜出行。
        // The common NONE path above is one locking read. On first use, use a separate INSERT
        // command and then SELECT: PostgreSQL's statement snapshot cannot see a row inserted by
        // an unreferenced data-modifying CTE in the same command. ON CONFLICT also serializes two
        // first requests for the same session; the following command sees the committed winner.
        jdbc.sql(
                        """
                        INSERT INTO platform_role_preview_context(session_primary_id,actor_id,revision,state)
                        SELECT primary_id,:actor,:revision,'NONE'
                          FROM spring_session
                         WHERE session_id=CAST(:session AS character(36)) AND principal_name=:principal
                            AND expiry_time > (extract(epoch FROM clock_timestamp())*1000)::bigint
                        ON CONFLICT(session_primary_id) DO NOTHING
                        """)
                .param("session", session.getId())
                .param("principal", principalName)
                .param("actor", actorId)
                .param("revision", UUID.randomUUID())
                .update();
        return contextForAdmission(
                        session.getId(), principalName, actorId, true, !skipGlobalAuthoritiesQuery)
                .or(() -> activeContextForUpdate(session.getId(), principalName, actorId))
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED, "authenticationRequired"));
    }

    private java.util.Optional<StoredContext> activeContextForUpdate(
            String sessionId, String principalName, UUID actorId) {
        return contextForAdmission(sessionId, principalName, actorId, false, false);
    }

    private java.util.Optional<StoredContext> contextForAdmission(
            String sessionId,
            String principalName,
            UUID actorId,
            boolean normalOnly,
            boolean loadGlobalAuthorities) {
        String globalSubquery;
        if (normalOnly && loadGlobalAuthorities) {
            if (isGlobalAuthorityView()) {
                globalSubquery =
                        """
                        ,(SELECT coalesce(string_agg(DISTINCT sub.authority, chr(31) ORDER BY sub.authority), '')
                             FROM (
                                 SELECT a.authority
                                   FROM platform_authority a
                                  WHERE a.user_id = c.actor_id
                                    AND a.authority NOT LIKE 'platform:__v50:%'
                                    AND NOT EXISTS (
                                        SELECT 1 FROM platform_permission_retired_authority ret
                                         WHERE ret.authority = a.authority
                                    )
                                 UNION ALL
                                 SELECT ra.authority
                                   FROM platform_user_role ur
                                   JOIN platform_role r ON r.id = ur.role_id AND r.enabled AND r.data_scope = 'ALL'
                                   JOIN platform_role_authority ra ON ra.role_id = r.id
                                  WHERE ur.user_id = c.actor_id
                                    AND ra.authority NOT LIKE 'platform:__v50:%'
                                    AND NOT EXISTS (
                                        SELECT 1 FROM platform_permission_retired_authority ret
                                         WHERE ret.authority = ra.authority
                                    )
                             ) sub) AS global_authorities
                        """;
            } else if (hasRelation("platform_global_authority")) {
                globalSubquery =
                        """
                        ,(SELECT coalesce(string_agg(DISTINCT g.authority, chr(31) ORDER BY g.authority), '')
                             FROM platform_global_authority g WHERE g.user_id = c.actor_id) AS global_authorities
                        """;
            } else {
                globalSubquery = ", '' AS global_authorities ";
            }
        } else {
            globalSubquery = " ";
        }
        String sql =
                "SELECT "
                        + CONTEXT_COLUMNS
                        + globalSubquery
                        + """
                          FROM spring_session s
                          JOIN platform_role_preview_context c
                            ON c.session_primary_id=s.primary_id
                         WHERE s.session_id=CAST(:session AS character(36)) AND s.principal_name=:principal
                           AND s.expiry_time > (extract(epoch FROM clock_timestamp())*1000)::bigint
                           AND c.actor_id=:actor
                        """
                        + (normalOnly ? " AND c.state='NONE' FOR SHARE OF c" : " FOR UPDATE OF c");
        return jdbc.sql(sql)
                .param("session", sessionId)
                .param("principal", principalName)
                .param("actor", actorId)
                .query(
                        (normalOnly && loadGlobalAuthorities)
                                ? this::mapAdmissionContext
                                : this::mapContext)
                .optional();
    }

    private StoredContext lockContext(String primaryId, UUID actorId) {
        return queryStoredContext(
                "SELECT "
                        + CONTEXT_COLUMNS
                        + """
                          FROM platform_role_preview_context c
                         WHERE c.session_primary_id=CAST(:session AS character(36)) AND c.actor_id=:actor
                           FOR UPDATE
                        """,
                primaryId,
                actorId);
    }

    private StoredContext mapAdmissionContext(java.sql.ResultSet rs, int row)
            throws java.sql.SQLException {
        String ga = rs.getString("global_authorities");
        return readStoredContext(rs, ga != null ? splitAuthorities(ga) : null);
    }

    private StoredContext mapContext(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return readStoredContext(rs, null);
    }

    private StoredContext readStoredContext(java.sql.ResultSet rs, Set<String> globalAuthorities)
            throws java.sql.SQLException {
        return new StoredContext(
                rs.getString("session_primary_id").trim(),
                rs.getObject("actor_id", UUID.class),
                rs.getObject("revision", UUID.class),
                rs.getString("state"),
                rs.getObject("role_id", UUID.class),
                rs.getString("role_code"),
                rs.getString("role_name"),
                (Long) rs.getObject("role_version"),
                rs.getString("data_scope"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getBoolean("limited_by_actor"),
                rs.getString("blocked_reason"),
                trimToNull(rs.getString("actor_authority_digest")),
                trimToNull(rs.getString("role_authority_digest")),
                globalAuthorities);
    }

    private String invalidPreviewReason(
            StoredContext stored, String currentActorDigest, UUID actorId, Set<String> realGlobal) {
        if (stored.expiresAt() == null || !stored.expiresAt().isAfter(OffsetDateTime.now()))
            return "EXPIRED";
        RoleSnapshot target = role(stored.roleId(), true);
        if (target == null || !target.enabled()) return "ROLE_UNAVAILABLE";
        if (target.version() != stored.roleVersion()
                || !Objects.equals(digest(target.authorities()), stored.roleAuthorityDigest()))
            return "ROLE_CHANGED";
        if (!Objects.equals(stored.actorAuthorityDigest(), currentActorDigest))
            return "ACTOR_AUTHORITY_CHANGED";
        if (!assigned(actorId, stored.roleId())
                && !(realGlobal.contains(PREVIEW_PERMISSION)
                        && realGlobal.contains(ROLE_ACCESS_PERMISSION)))
            return "PREVIEW_PERMISSION_REVOKED";
        return null;
    }

    private void block(StoredContext stored, String reason) {
        jdbc.sql(
                        """
                        UPDATE platform_role_preview_context
                           SET state='BLOCKED',revision=:revision,blocked_reason=:reason,
                               updated_at=CURRENT_TIMESTAMP
                         WHERE session_primary_id=CAST(:session AS character(36)) AND revision=:expected
                        """)
                .param("revision", UUID.randomUUID())
                .param("reason", reason)
                .param("session", stored.sessionPrimaryId())
                .param("expected", stored.revision())
                .update();
    }

    private RoleSnapshot role(UUID id, boolean lock) {
        if (id == null) return null;
        RoleSnapshot base =
                jdbc.sql(
                                """
                        SELECT id,code,name,enabled,version,data_scope
                          FROM platform_role WHERE id=:id
                        """
                                        + (lock ? " FOR SHARE" : ""))
                        .param("id", id)
                        .query(
                                (rs, row) ->
                                        new RoleSnapshot(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("code"),
                                                rs.getString("name"),
                                                rs.getBoolean("enabled"),
                                                rs.getLong("version"),
                                                rs.getString("data_scope"),
                                                Set.of()))
                        .optional()
                        .orElse(null);
        if (base == null) return null;
        Set<String> authorities =
                Set.copyOf(
                        jdbc.sql(
                                        "SELECT authority FROM platform_role_authority "
                                                + "WHERE role_id=:id "
                                                + "AND authority NOT LIKE 'platform:__v50:%' "
                                                + "ORDER BY authority")
                                .param("id", id)
                                .query(String.class)
                                .list());
        return new RoleSnapshot(
                base.id(),
                base.code(),
                base.name(),
                base.enabled(),
                base.version(),
                base.dataScope(),
                authorities);
    }

    private Set<String> globalAuthorities(UUID actorId) {
        if (isGlobalAuthorityView()) {
            Set<String> result = new HashSet<>();
            String sql =
                    """
                    SELECT a.authority
                      FROM platform_authority a
                     WHERE a.user_id=:actor
                       AND a.authority NOT LIKE 'platform:__v50:%'
                       AND NOT EXISTS (
                           SELECT 1 FROM platform_permission_retired_authority ret
                            WHERE ret.authority = a.authority
                       )
                    UNION ALL
                    SELECT ra.authority
                      FROM platform_user_role ur
                      JOIN platform_role r ON r.id=ur.role_id AND r.enabled AND r.data_scope='ALL'
                      JOIN platform_role_authority ra ON ra.role_id=r.id
                     WHERE ur.user_id=:actor
                       AND ra.authority NOT LIKE 'platform:__v50:%'
                       AND NOT EXISTS (
                           SELECT 1 FROM platform_permission_retired_authority ret
                            WHERE ret.authority = ra.authority
                       )
                    """;
            jdbc.sql(sql)
                    .param("actor", actorId)
                    .query(
                            (org.springframework.jdbc.core.RowCallbackHandler)
                                    rs -> {
                                        String auth = rs.getString(1);
                                        if (auth != null) result.add(auth);
                                    });
            return ActivePermissionCatalog.retain(result);
        }
        if (hasRelation("platform_global_authority")) {
            Set<String> result = new HashSet<>();
            String sql =
                    """
                    SELECT g.authority
                      FROM platform_global_authority g
                     WHERE g.user_id=:actor
                     ORDER BY g.authority
                    """;
            jdbc.sql(sql)
                    .param("actor", actorId)
                    .query(
                            (org.springframework.jdbc.core.RowCallbackHandler)
                                    rs -> {
                                        String auth = rs.getString(1);
                                        if (auth != null) result.add(auth);
                                    });
            return ActivePermissionCatalog.retain(result);
        }
        return scopeSnapshot(actorId).globalAuthorities();
    }

    private Set<String> effectiveAuthorities(UUID actorId) {
        Set<String> result = new HashSet<>();
        String sql =
                isEffectiveAuthorityView()
                        ? """
                          SELECT a.authority
                            FROM platform_authority a
                           WHERE a.user_id=:actor
                             AND a.authority NOT LIKE 'platform:__v50:%'
                             AND NOT EXISTS (
                                 SELECT 1 FROM platform_permission_retired_authority ret
                                  WHERE ret.authority = a.authority
                             )
                          UNION ALL
                          SELECT ra.authority
                            FROM platform_user_role ur
                            JOIN platform_role r ON r.id=ur.role_id AND r.enabled
                            JOIN platform_role_authority ra ON ra.role_id=r.id
                           WHERE ur.user_id=:actor
                             AND ra.authority NOT LIKE 'platform:__v50:%'
                             AND NOT EXISTS (
                                 SELECT 1 FROM platform_permission_retired_authority ret
                                  WHERE ret.authority = ra.authority
                             )
                          """
                        : "SELECT authority FROM platform_effective_authority WHERE user_id=:actor ORDER BY authority";
        jdbc.sql(sql)
                .param("actor", actorId)
                .query(
                        (org.springframework.jdbc.core.RowCallbackHandler)
                                rs -> {
                                    String auth = rs.getString(1);
                                    if (auth != null) result.add(auth);
                                });
        return ActivePermissionCatalog.retain(result);
    }

    private ScopeSnapshot scopeSnapshot(UUID actorId) {
        Map<String, Set<String>> values = new HashMap<>();
        Set<String> global = new HashSet<>();
        String sql =
                isEffectiveAuthorityView()
                        ? """
                          WITH scope_sources AS (
                              SELECT a.authority, 'ALL'::text AS data_scope
                                FROM platform_authority a
                               WHERE a.user_id=:actor
                                 AND a.authority NOT LIKE 'platform:__v50:%'
                                 AND NOT EXISTS (
                                     SELECT 1 FROM platform_permission_retired_authority ret
                                      WHERE ret.authority = a.authority
                                 )
                              UNION ALL
                              SELECT ra.authority, r.data_scope
                                FROM platform_user_role ur
                                JOIN platform_role r ON r.id=ur.role_id AND r.enabled
                                JOIN platform_role_authority ra ON ra.role_id=r.id
                               WHERE ur.user_id=:actor
                                 AND ra.authority NOT LIKE 'platform:__v50:%'
                                 AND NOT EXISTS (
                                     SELECT 1 FROM platform_permission_retired_authority ret
                                      WHERE ret.authority = ra.authority
                                 )
                          )
                          SELECT authority, data_scope FROM scope_sources
                           ORDER BY authority, data_scope
                          """
                        : """
                          WITH effective AS (
                              SELECT authority FROM platform_effective_authority WHERE user_id=:actor
                          ), scope_sources AS (
                              SELECT a.authority,'ALL'::text AS data_scope
                                FROM platform_authority a
                                JOIN effective ON effective.authority=a.authority
                               WHERE a.user_id=:actor
                              UNION ALL
                              SELECT ra.authority,r.data_scope
                                FROM platform_user_role ur
                                JOIN platform_role r ON r.id=ur.role_id AND r.enabled
                                JOIN platform_role_authority ra ON ra.role_id=r.id
                                JOIN effective ON effective.authority=ra.authority
                               WHERE ur.user_id=:actor
                          )
                          SELECT authority,data_scope FROM scope_sources
                           ORDER BY authority,data_scope
                          """;
        jdbc.sql(sql)
                .param("actor", actorId)
                .query(
                        (rs, row) ->
                                Map.entry(rs.getString("authority"), rs.getString("data_scope")))
                .list()
                .forEach(
                        entry -> {
                            if (!ActivePermissionCatalog.current().contains(entry.getKey())) return;
                            values.computeIfAbsent(entry.getKey(), ignored -> new TreeSet<>())
                                    .add(entry.getValue());
                            if ("ALL".equals(entry.getValue())) global.add(entry.getKey());
                        });
        var result = new LinkedHashMap<String, Set<String>>();
        values.forEach((permission, scopes) -> result.put(permission, Set.copyOf(scopes)));
        return new ScopeSnapshot(Set.copyOf(global), Map.copyOf(result));
    }

    private String authorityDigest(
            UUID actorId,
            Set<String> realAuthorities,
            Set<String> realGlobal,
            Map<String, Set<String>> realScopes) {
        var parts = new ArrayList<String>();
        realAuthorities.stream().sorted().forEach(value -> parts.add("A:" + value));
        realGlobal.stream().sorted().forEach(value -> parts.add("G:" + value));
        realScopes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        entry ->
                                entry.getValue().stream()
                                        .sorted()
                                        .forEach(
                                                scope ->
                                                        parts.add(
                                                                "S:"
                                                                        + entry.getKey()
                                                                        + ":"
                                                                        + scope)));
        jdbc.sql(
                        """
                        SELECT r.id,r.version,r.data_scope
                          FROM platform_user_role ur JOIN platform_role r ON r.id=ur.role_id
                         WHERE ur.user_id=:actor ORDER BY r.id
                        """)
                .param("actor", actorId)
                .query(
                        (rs, row) ->
                                "R:"
                                        + rs.getObject("id", UUID.class)
                                        + ":"
                                        + rs.getLong("version")
                                        + ":"
                                        + rs.getString("data_scope"))
                .list()
                .forEach(parts::add);
        return digest(parts);
    }

    private boolean assigned(UUID actorId, UUID roleId) {
        return Boolean.TRUE.equals(
                jdbc.sql(
                                "SELECT EXISTS(SELECT 1 FROM platform_user_role WHERE user_id=:actor AND role_id=:role)")
                        .param("actor", actorId)
                        .param("role", roleId)
                        .query(rs -> rs.next() && rs.getBoolean(1)));
    }

    private boolean limitedByActor(
            Set<String> realAuthorities,
            Set<String> realGlobal,
            Map<String, Set<String>> realScopes,
            Set<String> targetAuthorities,
            String targetScope) {
        if (!realAuthorities.containsAll(targetAuthorities)) return true;
        for (String permission : targetAuthorities) {
            Set<String> scopes = realScopes.getOrDefault(permission, Set.of());
            if (scopeRank(targetScope)
                            > scopes.stream()
                                    .mapToInt(RolePreviewContextService::scopeRank)
                                    .max()
                                    .orElse(0)
                    && !realGlobal.contains(permission)) return true;
        }
        return false;
    }

    private static int scopeRank(String scope) {
        return switch (scope == null ? "" : scope) {
            case "SELF" -> 1;
            case "ORGANIZATION" -> 2;
            case "SUBTREE" -> 3;
            case "ALL" -> 4;
            default -> 0;
        };
    }

    private static Map<String, Set<String>> intersectScopes(
            Map<String, Set<String>> realScopes, Set<String> authorities, String targetScope) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String permission : authorities) {
            Set<String> values = new HashSet<>();
            for (String real : realScopes.getOrDefault(permission, Set.of())) {
                String intersection = narrowerScope(real, targetScope);
                if (intersection != null) values.add(intersection);
            }
            if (!values.isEmpty()) result.put(permission, Set.copyOf(values));
        }
        return Map.copyOf(result);
    }

    private static String narrowerScope(String first, String second) {
        if ("ALL".equals(first)) return second;
        if ("ALL".equals(second)) return first;
        if ("SELF".equals(first) || "SELF".equals(second)) return "SELF";
        if ("ORGANIZATION".equals(first) || "ORGANIZATION".equals(second)) return "ORGANIZATION";
        if ("SUBTREE".equals(first) || "SUBTREE".equals(second)) return "SUBTREE";
        return null;
    }

    private static Set<String> splitAuthorities(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Set.copyOf(new HashSet<>(Arrays.asList(value.split(String.valueOf((char) 31)))));
    }

    private void requireGlobalBootstrapOperator(UUID actorId) {
        Set<String> grants = globalAuthorities(actorId);
        if (!grants.contains("platform:roles:update")
                || !grants.contains("platform:settings:update"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }

    private boolean hasDirectPreviewGrant(UUID userId) {
        return Boolean.TRUE.equals(
                jdbc.sql(
                                "SELECT EXISTS(SELECT 1 FROM platform_authority WHERE user_id=:user AND authority=:permission)")
                        .param("user", userId)
                        .param("permission", PREVIEW_PERMISSION)
                        .query(rs -> rs.next() && rs.getBoolean(1)));
    }

    private static RequestAuthorizationContext authorization(HttpServletRequest request) {
        AuthenticationRequestContext.Snapshot snapshot = AuthenticationRequestContext.current();
        if (snapshot == null || snapshot.authorization() == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authenticationRequired");
        return snapshot.authorization();
    }

    private static void requireRevision(StoredContext current, String expectedRevision) {
        if (expectedRevision == null || !current.revision().toString().equals(expectedRevision))
            throw changed();
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "authorizationContextChanged");
    }

    private static Preview preview(StoredContext stored) {
        return stored.roleId() == null
                ? null
                : new Preview(
                        stored.state(),
                        stored.roleId(),
                        stored.roleName(),
                        stored.roleVersion() == null ? 0 : stored.roleVersion(),
                        stored.startedAt(),
                        stored.expiresAt(),
                        stored.limitedByActor(),
                        stored.blockedReason());
    }

    private static RequestAuthorizationContext.Preview authorizationPreview(StoredContext stored) {
        return stored.roleId() == null
                ? null
                : new RequestAuthorizationContext.Preview(
                        stored.state(),
                        stored.roleId(),
                        stored.roleName(),
                        stored.roleVersion() == null ? 0 : stored.roleVersion(),
                        stored.startedAt(),
                        stored.expiresAt(),
                        stored.limitedByActor(),
                        stored.blockedReason());
    }

    private static View view(StoredContext stored) {
        return new View(stored.revision().toString(), true, preview(stored));
    }

    private static View view(RequestAuthorizationContext context) {
        RequestAuthorizationContext.Preview preview = context.preview();
        return new View(
                context.revision(),
                context.rolePreviewSupported(),
                preview == null
                        ? null
                        : new Preview(
                                preview.state(),
                                preview.roleId(),
                                preview.roleName(),
                                preview.roleVersion(),
                                preview.startedAt(),
                                preview.expiresAt(),
                                preview.limitedByActor(),
                                preview.blockedReason()));
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String digest(Set<String> values) {
        return digest(values.stream().sorted().toList());
    }

    private static String digest(List<String> values) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(String.join("\n", values).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
