/**
 * 复核管理权限、目标可见性和预览范围，并只在写事务内取得相应一致性锁，避免只读查询等待无关管理写入。 Rechecks administrative grants, target
 * visibility and preview scope, taking consistency locks only in write transactions so read-only
 * queries do not wait on unrelated administration writes.
 */
package com.lumira.platform.identity;

import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.AuthorizationContextBoundaryPort;
import com.lumira.platform.identity.api.RequestAuthorizationContext;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * 在写事务内调用以与并发管理操作串行化；每次都重新读取授权。
 *
 * <p>Call inside a write transaction to serialize with concurrent administration; grants are always
 * re-read.
 */
@Component
public class AdministrationGuard
        implements com.lumira.platform.common.AdministrationPort,
                com.lumira.platform.modules.ModuleAdministrationAuthorizer {
    private static final long SHARED_SCHEMA_LOCK = 481026L;
    private static final long SECTION_LOCK_RANGE = 1_000_000_000L;
    private static final long ADMIN_RECOVERY_LOCK =
            com.lumira.platform.common.AdministrationRecoveryBarrier.LOCK_KEY;
    private static final String PLATFORM_PERMISSION_PREFIX = "platform:";
    private static final Set<String> SHARED_SCHEMA_PAGES =
            Set.of(
                    "settings",
                    "branding",
                    "smtp",
                    "sms",
                    "profile-fields",
                    "agreements",
                    "watermark",
                    "maintenance",
                    "security",
                    "authentication",
                    "passkey",
                    "wechat",
                    "captcha",
                    "registration",
                    "file-retention");
    private static final Set<String> RECOVERY_ADMINISTRATION_WRITES =
            Set.of(
                    "platform:users:create",
                    "platform:users:update",
                    "platform:users:delete",
                    "platform:users:reset-password",
                    "platform:users:assign-roles",
                    "platform:users:assign-organizations",
                    "platform:ai-console:update-user",
                    "platform:ai-console:delete-user",
                    "platform:roles:create",
                    "platform:roles:update",
                    "platform:roles:delete",
                    "platform:roles:update-authentication-policy",
                    "platform:organizations:create",
                    "platform:organizations:update",
                    "platform:organizations:delete",
                    "platform:dictionaries:create",
                    "platform:dictionaries:update",
                    "platform:dictionaries:delete",
                    "platform:dictionaries:import",
                    "platform:branding:update",
                    "platform:smtp:update",
                    "platform:sms:update",
                    "platform:profile-fields:update",
                    "platform:agreements:update",
                    "platform:watermark:update",
                    "platform:maintenance:update",
                    "platform:security:update",
                    "platform:authentication:update",
                    "platform:passkey:update",
                    "platform:wechat:update",
                    "platform:captcha:update",
                    "platform:registration:update",
                    "platform:registration:assign-default-role",
                    "platform:file-retention:update",
                    "platform:ai-models:create",
                    "platform:ai-models:update",
                    "platform:ai-models:delete",
                    "platform:modules:install",
                    "platform:modules:update",
                    "platform:modules:uninstall",
                    "platform:navigation:create",
                    "platform:navigation:update",
                    "platform:navigation:delete",
                    "platform:files-all:delete",
                    "platform:files-all:update-quota",
                    "platform:files-all:purge",
                    "platform:files-all:restore",
                    "platform:event-outbox:replay",
                    "platform:notifications:create");
    private final JdbcClient jdbc;
    private final AuthorizationContextBoundaryPort authorizationBoundary;

    public AdministrationGuard(JdbcClient jdbc) {
        this(jdbc, null);
    }

    @Autowired
    public AdministrationGuard(
            JdbcClient jdbc, AuthorizationContextBoundaryPort authorizationBoundary) {
        this.jdbc = jdbc;
        this.authorizationBoundary = authorizationBoundary;
    }

    private final RolePreviewContextService.SchemaRelationState schemaRelations =
            new RolePreviewContextService.SchemaRelationState();

    private boolean isEffectiveAuthorityView() {
        return schemaRelations.isEffectiveAuthorityView(jdbc);
    }

    private boolean hasRelation(String relationName) {
        return schemaRelations.hasRelation(jdbc, relationName);
    }

    private static final class ActiveLocksSynchronization
            implements org.springframework.transaction.support.TransactionSynchronization {
        private final Set<Long> exclusiveLocks = new HashSet<>();
        private final Set<Long> sharedLocks = new HashSet<>();
    }

    private record Grants(Set<String> effective, Set<String> global) {}

    private static final String EFFECTIVE_VIEW_GRANTS_TEMPLATE =
            """
            WITH actor AS NOT MATERIALIZED (
                SELECT id FROM platform_user WHERE <ACTOR_CONDITION> AND enabled
            ),
            direct_grants AS (
                SELECT a.authority, 'ALL'::text AS data_scope
                  FROM platform_authority a
                  JOIN actor ON actor.id = a.user_id
                 WHERE a.authority NOT LIKE 'platform:__v50:%'
                   AND NOT EXISTS (
                       SELECT 1 FROM platform_permission_retired_authority ret
                        WHERE ret.authority = a.authority
                   )
            ),
            role_grants AS (
                SELECT ra.authority, r.data_scope
                  FROM platform_user_role ur
                  JOIN actor ON actor.id = ur.user_id
                  JOIN platform_role r ON r.id = ur.role_id AND r.enabled
                  JOIN platform_role_authority ra ON ra.role_id = r.id
                 WHERE ra.authority NOT LIKE 'platform:__v50:%'
                   AND NOT EXISTS (
                       SELECT 1 FROM platform_permission_retired_authority ret
                        WHERE ret.authority = ra.authority
                   )
            ),
            all_grants AS (
                SELECT authority, data_scope FROM direct_grants
                UNION ALL
                SELECT authority, data_scope FROM role_grants
            )
            SELECT 'E' AS kind, authority FROM all_grants
            UNION ALL
            SELECT 'G' AS kind, authority FROM all_grants WHERE data_scope = 'ALL'
            """;

    private static final String COMBINED_PREVIEW_NONE_SUFFIX =
            """
            UNION ALL
            SELECT 'C' AS kind, c.state || ':' || c.revision::text AS authority
              FROM platform_role_preview_context c
             WHERE c.session_primary_id = CAST(:session AS character(36))
               AND c.actor_id = :actorId
            """;

    private static final String FALLBACK_GLOBAL_GRANTS_TEMPLATE =
            """
            WITH actor AS NOT MATERIALIZED (
                SELECT id FROM platform_user WHERE <ACTOR_CONDITION> AND enabled
            )
            SELECT 'E' AS kind, pea.authority
              FROM platform_effective_authority pea
              JOIN actor ON actor.id = pea.user_id
            UNION ALL
            SELECT 'G' AS kind, pga.authority
              FROM platform_global_authority pga
              JOIN actor ON actor.id = pga.user_id
            """;

    private static final String FALLBACK_EFFECTIVE_GRANTS_TEMPLATE =
            """
            WITH actor AS NOT MATERIALIZED (
                SELECT id FROM platform_user WHERE <ACTOR_CONDITION> AND enabled
            )
            SELECT 'E' AS kind, pea.authority
              FROM platform_effective_authority pea
              JOIN actor ON actor.id = pea.user_id
            """;

    private Grants loadGrants(String actor) {
        UUID actorId = currentActorId(actor);
        Set<String> effective = new HashSet<>();
        Set<String> global = new HashSet<>();
        String actorCondition = actorId != null ? "id = :actorId" : "username = :actor";
        String sql;
        if (isEffectiveAuthorityView()) {
            sql = EFFECTIVE_VIEW_GRANTS_TEMPLATE.replace("<ACTOR_CONDITION>", actorCondition);
        } else {
            boolean hasGlobal = hasRelation("platform_global_authority");
            sql =
                    (hasGlobal
                                    ? FALLBACK_GLOBAL_GRANTS_TEMPLATE
                                    : FALLBACK_EFFECTIVE_GRANTS_TEMPLATE)
                            .replace("<ACTOR_CONDITION>", actorCondition);
        }
        (actorId != null
                        ? jdbc.sql(sql).param("actorId", actorId)
                        : jdbc.sql(sql).param("actor", actor))
                .query(
                        (org.springframework.jdbc.core.RowCallbackHandler)
                                rs -> {
                                    String kind = rs.getString(1);
                                    String authority = rs.getString(2);
                                    if (authority != null) {
                                        if ("E".equals(kind)) effective.add(authority);
                                        else if ("G".equals(kind)) global.add(authority);
                                    }
                                });
        var active = com.lumira.platform.modules.ActivePermissionCatalog.current();
        effective.retainAll(active);
        global.retainAll(active);
        return new Grants(effective, global);
    }

    private Grants tryLoadReadOnlyBoundaryAndGrants(String actor) {
        if (serializesWrites()) return null;
        if (!(authorizationBoundary instanceof RolePreviewContextService previewService)) {
            return null;
        }
        RequestAuthorizationContext requestContext = currentAuthorization(actor);
        if (requestContext == null) return null;
        UUID actorId = currentActorId(actor);
        if (actorId == null) return null;
        if (!isEffectiveAuthorityView()) return null;
        if (!previewService.hasRelation("platform_role_preview_context")) return null;
        if (!previewService.canCombineReadOnlyNoneBoundary(requestContext)) return null;

        Set<String> effective = new HashSet<>();
        Set<String> global = new HashSet<>();
        String[] stateAndRev = {null};
        String sql =
                EFFECTIVE_VIEW_GRANTS_TEMPLATE.replace("<ACTOR_CONDITION>", "id = :actorId")
                        + COMBINED_PREVIEW_NONE_SUFFIX;
        jdbc.sql(sql)
                .param("actorId", actorId)
                .param("session", requestContext.sessionPrimaryId())
                .query(
                        (org.springframework.jdbc.core.RowCallbackHandler)
                                rs -> {
                                    String kind = rs.getString(1);
                                    String auth = rs.getString(2);
                                    if (kind != null && auth != null) {
                                        switch (kind) {
                                            case "E" -> effective.add(auth);
                                            case "G" -> global.add(auth);
                                            case "C" -> stateAndRev[0] = auth;
                                            default -> {}
                                        }
                                    }
                                });
        if (!previewService.completeCombinedReadOnlyNoneBoundary(requestContext, stateAndRev[0])) {
            return null;
        }
        var active = com.lumira.platform.modules.ActivePermissionCatalog.current();
        effective.retainAll(active);
        global.retainAll(active);
        return new Grants(effective, global);
    }

    public Set<String> require(String actor, String permission) {
        Grants grants = tryLoadReadOnlyBoundaryAndGrants(actor);
        if (grants != null) {
            if (!grants.effective().contains(permission)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
            }
            return grants.effective();
        }
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
        if (serializesWrites()) {
            if (isRecoveryAdministrationWrite(permission)) lock(ADMIN_RECOVERY_LOCK);
            lock(lockKey(permission));
        }
        return requireAuthority(actor, permission);
    }

    @Override
    public Set<String> requireConcurrentOperationGlobal(String actor, PlatformOperation operation) {
        // 所有常规字典/文件、个人资料设置及模块写操作在变更数据前均获取排他恢复屏障；保持该兼容边界共享，不得在其前后额外获取分段锁（历史写路径已持有锁）。
        // Every ordinary dictionary/file, profile-settings and module writer takes the exclusive
        // recovery barrier before changing data. Keep that compatibility boundary shared;
        // do not acquire a section lock before/after it (legacy writers already hold one).
        if (operation != PlatformOperation.DICTIONARY_CREATE
                && operation != PlatformOperation.DICTIONARY_UPDATE
                && operation != PlatformOperation.DICTIONARY_DELETE
                && operation != PlatformOperation.FILE_DELETE
                && operation != PlatformOperation.FILE_RESTORE
                && operation != PlatformOperation.FILE_PURGE)
            return com.lumira.platform.common.AdministrationPort.super
                    .requireConcurrentOperationGlobal(actor, operation);
        if (!serializesWrites())
            throw new IllegalStateException("Concurrent writes require a writable transaction");
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
        lock(ADMIN_RECOVERY_LOCK, true);
        return checkOperationGlobal(
                actor, operation.page().accessPermission(), operation.permission());
    }

    @Override
    public Set<String> requireOperationGlobal(String actor, PlatformOperation operation) {
        String pagePerm = operation.page().accessPermission();
        String actionPerm = operation.permission();
        lockOperation(pagePerm, actionPerm);
        return checkOperationGlobal(actor, pagePerm, actionPerm);
    }

    @Override
    public Set<String> requireOperationActionGlobal(String actor, PlatformOperation operation) {
        String pagePerm = operation.page().accessPermission();
        String actionPerm = operation.permission();
        lockOperation(pagePerm, actionPerm);
        boolean pageGlobal = operation.page().scope() == PlatformOperation.Scope.GLOBAL;
        Grants grants = resolveGrants(actor);
        return validateOperationAuthorities(
                grants.effective(), grants.global(), pagePerm, actionPerm, pageGlobal, true);
    }

    @Override
    public Set<String> requireOperation(String actor, PlatformOperation operation) {
        String pagePerm = operation.page().accessPermission();
        String actionPerm = operation.permission();
        lockOperation(pagePerm, actionPerm);
        boolean pageGlobal = operation.page().scope() == PlatformOperation.Scope.GLOBAL;
        boolean actionGlobal = operation.scope() == PlatformOperation.Scope.GLOBAL;
        RequestAuthorizationContext requestContext = currentAuthorization(actor);
        Set<String> held;
        Set<String> global;
        if (requestContext != null && requestContext.previewActive()) {
            held = requestContext.effectiveAuthorities();
            global = requestContext.effectiveGlobalAuthorities();
        } else if (pageGlobal || actionGlobal) {
            Grants grants = loadGrants(actor);
            held = grants.effective();
            global = grants.global();
        } else {
            held = requireAuthority(actor, pagePerm);
            global = Set.of();
        }
        return validateOperationAuthorities(
                held, global, pagePerm, actionPerm, pageGlobal, actionGlobal);
    }

    private Grants resolveGrants(String actor) {
        RequestAuthorizationContext requestContext = currentAuthorization(actor);
        if (requestContext != null && requestContext.previewActive()) {
            return new Grants(
                    requestContext.effectiveAuthorities(),
                    requestContext.effectiveGlobalAuthorities());
        }
        return loadGrants(actor);
    }

    private void lockOperation(String pagePerm, String actionPerm) {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
        if (serializesWrites()) {
            if (isRecoveryAdministrationWrite(pagePerm)
                    || isRecoveryAdministrationWrite(actionPerm)) {
                lock(ADMIN_RECOVERY_LOCK);
            }
            long pageKey = lockKey(pagePerm);
            long actionKey = lockKey(actionPerm);
            if (pageKey == actionKey) {
                lock(pageKey);
            } else if (pageKey < actionKey) {
                lock(pageKey);
                lock(actionKey);
            } else {
                lock(actionKey);
                lock(pageKey);
            }
        }
    }

    private static Set<String> validateOperationAuthorities(
            Set<String> held,
            Set<String> global,
            String pagePerm,
            String actionPerm,
            boolean pageGlobal,
            boolean actionGlobal) {
        if (!held.contains(pagePerm)
                || !held.contains(actionPerm)
                || (pageGlobal && !global.contains(pagePerm))
                || (actionGlobal && !global.contains(actionPerm))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return Set.copyOf(held);
    }

    private Set<String> checkOperationGlobal(String actor, String pagePerm, String actionPerm) {
        Grants grants = resolveGrants(actor);
        return validateOperationAuthorities(
                grants.effective(), grants.global(), pagePerm, actionPerm, true, true);
    }

    private static final String EFFECTIVE_VIEW_AUTHORITIES_TEMPLATE =
            """
            WITH actor AS NOT MATERIALIZED (
                SELECT id FROM platform_user WHERE <ACTOR_CONDITION> AND enabled
            ),
            direct_grants AS (
                SELECT a.authority
                  FROM platform_authority a
                  JOIN actor ON actor.id = a.user_id
                 WHERE a.authority NOT LIKE 'platform:__v50:%'
                   AND NOT EXISTS (
                       SELECT 1 FROM platform_permission_retired_authority ret
                        WHERE ret.authority = a.authority
                   )
            ),
            role_grants AS (
                SELECT ra.authority
                  FROM platform_user_role ur
                  JOIN actor ON actor.id = ur.user_id
                  JOIN platform_role r ON r.id = ur.role_id AND r.enabled
                  JOIN platform_role_authority ra ON ra.role_id = r.id
                 WHERE ra.authority NOT LIKE 'platform:__v50:%'
                   AND NOT EXISTS (
                       SELECT 1 FROM platform_permission_retired_authority ret
                        WHERE ret.authority = ra.authority
                   )
            )
            SELECT authority FROM direct_grants
            UNION ALL
            SELECT authority FROM role_grants
            """;

    private static final String FALLBACK_AUTHORITIES_TEMPLATE =
            """
            WITH actor AS NOT MATERIALIZED (
                SELECT id FROM platform_user WHERE <ACTOR_CONDITION> AND enabled
            )
            SELECT pea.authority
              FROM platform_effective_authority pea
              JOIN actor ON actor.id = pea.user_id
            """;

    private Set<String> requireAuthority(String actor, String permission) {
        RequestAuthorizationContext requestContext = currentAuthorization(actor);
        Set<String> held;
        if (requestContext != null && requestContext.previewActive()) {
            held = new HashSet<>(requestContext.effectiveAuthorities());
        } else {
            UUID actorId = currentActorId(actor);
            held = new HashSet<>();
            String actorCondition = actorId != null ? "id = :actorId" : "username = :actor";
            String sql =
                    (isEffectiveAuthorityView()
                                    ? EFFECTIVE_VIEW_AUTHORITIES_TEMPLATE
                                    : FALLBACK_AUTHORITIES_TEMPLATE)
                            .replace("<ACTOR_CONDITION>", actorCondition);
            (actorId != null
                            ? jdbc.sql(sql).param("actorId", actorId)
                            : jdbc.sql(sql).param("actor", actor))
                    .query(
                            (org.springframework.jdbc.core.RowCallbackHandler)
                                    rs -> {
                                        String auth = rs.getString(1);
                                        if (auth != null) held.add(auth);
                                    });
        }
        held.retainAll(com.lumira.platform.modules.ActivePermissionCatalog.current());
        if (!held.contains(permission))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        return held;
    }

    /**
     * 咨询锁只用于排序写入；事务外会因自动提交立即释放，只读事务也不写入，二者获取此锁只会让读取等待无关管理操作，因此仅重新读取授权。
     *
     * <p>The advisory lock only orders writers. Outside a transaction it would be released at once
     * (autocommit), and a read-only transaction writes nothing, so in both cases taking it would
     * only make readers queue behind unrelated administration. Grants are re-read either way.
     */
    static boolean serializesWrites() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }

    private static boolean isRecoveryAdministrationWrite(String permission) {
        return RECOVERY_ADMINISTRATION_WRITES.contains(permission);
    }

    private void lock(long key) {
        lock(key, false);
    }

    private void lock(long key, boolean shared) {
        ActiveLocksSynchronization activeLocks = null;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
                if (sync instanceof ActiveLocksSynchronization s) {
                    activeLocks = s;
                    break;
                }
            }
            if (activeLocks == null) {
                activeLocks = new ActiveLocksSynchronization();
                TransactionSynchronizationManager.registerSynchronization(activeLocks);
            }
            if (shared) {
                if (activeLocks.exclusiveLocks.contains(key)
                        || activeLocks.sharedLocks.contains(key)) {
                    return;
                }
            } else {
                if (activeLocks.exclusiveLocks.contains(key)) {
                    return;
                }
            }
        }
        jdbc.sql(
                        shared
                                ? "SELECT pg_advisory_xact_lock_shared(:lockKey)"
                                : "SELECT pg_advisory_xact_lock(:lockKey)")
                .param("lockKey", key)
                .query(
                        rs -> {
                            rs.next();
                            return 0;
                        });
        if (activeLocks != null) {
            if (shared) {
                activeLocks.sharedLocks.add(key);
            } else {
                activeLocks.exclusiveLocks.add(key);
            }
        }
    }

    /**
     * 只串行化共享一致性边界的写入；设置与模块生命周期沿用历史首锁，其他权限域使用稳定咨询锁分组，使无关管理写入通常可跨实例并发。
     *
     * <p>Serialize only writes that share a consistency boundary. Settings and module lifecycle
     * keep the historical lock because profile/schema writes and the module graph use it as their
     * first lock. Other permission domains get stable advisory buckets so unrelated admin writes
     * can normally proceed concurrently across instances.
     */
    static long lockKey(String permission) {
        if (permission == null || permission.isBlank())
            throw new IllegalArgumentException("Permission is required");
        int first = permission.indexOf(':');
        int separator = permission.indexOf(':', first + 1);
        String section =
                permission.startsWith(PLATFORM_PERMISSION_PREFIX) && separator > 0
                        ? permission.substring(first + 1, separator)
                        : permission;
        if (SHARED_SCHEMA_PAGES.contains(section) || section.equals("modules"))
            return SHARED_SCHEMA_LOCK;
        long hash = 1469598103934665603L;
        for (int i = 0; i < section.length(); i++) {
            hash ^= section.charAt(i);
            hash *= 1099511628211L;
        }
        return SHARED_SCHEMA_LOCK + 1 + Math.floorMod(hash, SECTION_LOCK_RANGE);
    }

    public void requireUser(String actor, UUID target, String permission) {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
        RequestAuthorizationContext requestContext = currentAuthorization(actor);
        UUID targetRole =
                requestContext != null && requestContext.previewActive()
                        ? requestContext.targetRoleId()
                        : null;
        Long targetVersion =
                requestContext != null && requestContext.previewActive()
                        ? requestContext.preview().roleVersion()
                        : null;
        boolean allowed =
                Boolean.TRUE.equals(
                        targetRole == null
                                ? jdbc.sql(
                                                "SELECT EXISTS(SELECT 1 FROM platform_visible_users(:actor,:permission) WHERE user_id=:id)")
                                        .param("actor", actor)
                                        .param("permission", permission)
                                        .param("id", target)
                                        .query(rs -> rs.next() && rs.getBoolean(1))
                                : jdbc.sql(
                                                "SELECT EXISTS(SELECT 1 FROM platform_role_preview_visible_users(:actor,:permission,:targetRoleId,:targetRoleVersion) WHERE user_id=:id)")
                                        .param("actor", actor)
                                        .param("permission", permission)
                                        .param("targetRoleId", targetRole)
                                        .param("targetRoleVersion", targetVersion)
                                        .param("id", target)
                                        .query(rs -> rs.next() && rs.getBoolean(1)));
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }

    @Override
    public void requireOperationUser(String actor, UUID target, PlatformOperation operation) {
        requireOperation(actor, operation);
        String pagePerm = operation.page().accessPermission();
        String actionPerm = operation.permission();
        requireUser(actor, target, pagePerm);
        if (!actionPerm.equals(pagePerm)) {
            requireUser(actor, target, actionPerm);
        }
    }

    @Override
    public Set<String> requirePage(String actor, com.lumira.platform.common.PlatformPageId page) {
        if (page.scope() == PlatformOperation.Scope.GLOBAL) return requirePageGlobal(actor, page);
        return require(actor, page.accessPermission());
    }

    @Override
    public Set<String> requirePageGlobal(
            String actor, com.lumira.platform.common.PlatformPageId page) {
        String perm = page.accessPermission();
        Grants combined = tryLoadReadOnlyBoundaryAndGrants(actor);
        if (combined != null) {
            if (!combined.effective().contains(perm) || !combined.global().contains(perm)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
            }
            return Set.copyOf(combined.effective());
        }
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
        if (serializesWrites()) {
            if (isRecoveryAdministrationWrite(perm)) lock(ADMIN_RECOVERY_LOCK);
            lock(lockKey(perm));
        }
        Grants grants = resolveGrants(actor);
        if (!grants.effective().contains(perm) || !grants.global().contains(perm))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        return Set.copyOf(grants.effective());
    }

    public void requireGlobal(String actor, String permission) {
        if (!hasGlobal(actor, permission))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }

    public boolean hasGlobal(String actor, String permission) {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
        return hasGlobalWithoutBoundary(actor, permission);
    }

    @Override
    public boolean hasRequestGlobal(String actor, String permission) {
        RequestAuthorizationContext requestContext = currentAuthorization(actor);
        if (requestContext != null
                && !requestContext.previewActive()
                && !requestContext.previewBlocked()
                && !TransactionSynchronizationManager.isActualTransactionActive()) {
            return requestContext.effectiveGlobalAuthorities().contains(permission);
        }
        return hasGlobal(actor, permission);
    }

    private static final String EFFECTIVE_VIEW_HAS_GLOBAL_TEMPLATE =
            """
            WITH actor AS MATERIALIZED (
                SELECT id FROM platform_user WHERE <ACTOR_CONDITION> AND enabled
            )
            SELECT EXISTS(
                SELECT 1 FROM platform_authority a
                 JOIN actor ON actor.id = a.user_id
                WHERE a.authority = :permission
                  AND a.authority NOT LIKE 'platform:__v50:%'
                  AND NOT EXISTS (
                      SELECT 1 FROM platform_permission_retired_authority ret
                       WHERE ret.authority = a.authority
                  )
                UNION ALL
                SELECT 1 FROM platform_user_role ur
                 JOIN actor ON actor.id = ur.user_id
                 JOIN platform_role r ON r.id = ur.role_id AND r.enabled AND r.data_scope = 'ALL'
                 JOIN platform_role_authority ra ON ra.role_id = r.id
                WHERE ra.authority = :permission
                  AND ra.authority NOT LIKE 'platform:__v50:%'
                  AND NOT EXISTS (
                      SELECT 1 FROM platform_permission_retired_authority ret
                       WHERE ret.authority = ra.authority
                  )
            )
            """;

    private static final String FALLBACK_HAS_GLOBAL_TEMPLATE =
            """
            WITH actor AS MATERIALIZED (
                SELECT id FROM platform_user WHERE <ACTOR_CONDITION> AND enabled
            )
            SELECT EXISTS(
                SELECT 1 FROM platform_global_authority pga
                 JOIN actor ON actor.id = pga.user_id
                WHERE pga.authority = :permission
            )
            """;

    private boolean hasGlobalWithoutBoundary(String actor, String permission) {
        RequestAuthorizationContext requestContext = currentAuthorization(actor);
        if (requestContext != null && requestContext.previewActive())
            return requestContext.effectiveGlobalAuthorities().contains(permission);
        UUID actorId = currentActorId(actor);
        String actorCondition = actorId != null ? "id = :actorId" : "username = :actor";
        if (isEffectiveAuthorityView()) {
            String sql =
                    EFFECTIVE_VIEW_HAS_GLOBAL_TEMPLATE.replace("<ACTOR_CONDITION>", actorCondition);
            return queryHasGlobal(sql, actorId, actor, permission);
        }
        if (!hasRelation("platform_global_authority")) {
            return false;
        }
        String sql = FALLBACK_HAS_GLOBAL_TEMPLATE.replace("<ACTOR_CONDITION>", actorCondition);
        return queryHasGlobal(sql, actorId, actor, permission);
    }

    private boolean queryHasGlobal(String sql, UUID actorId, String actor, String permission) {
        return Boolean.TRUE.equals(
                (actorId != null
                                ? jdbc.sql(sql).param("actorId", actorId)
                                : jdbc.sql(sql).param("actor", actor))
                        .param("permission", permission)
                        .query(rs -> rs.next() && rs.getBoolean(1)));
    }

    private static UUID currentActorId(String actor) {
        AuthenticationRequestContext.Snapshot snapshot = AuthenticationRequestContext.current();
        if (snapshot == null
                || snapshot.userId() == null
                || !actor.equals(snapshot.principalName())) {
            return null;
        }
        return snapshot.userId();
    }

    private static RequestAuthorizationContext currentAuthorization(String actor) {
        AuthenticationRequestContext.Snapshot snapshot = AuthenticationRequestContext.current();
        if (snapshot == null
                || snapshot.authorization() == null
                || !actor.equals(snapshot.principalName())) return null;
        return snapshot.authorization();
    }
}
