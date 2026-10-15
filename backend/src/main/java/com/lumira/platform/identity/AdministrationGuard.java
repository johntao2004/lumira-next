package com.lumira.platform.identity;

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
 * Call inside a write transaction to serialize with concurrent administration; grants are always
 * re-read.
 */
@Component
public class AdministrationGuard
        implements com.lumira.platform.common.AdministrationPort,
                com.lumira.platform.modules.ModuleAdministrationAuthorizer {
    private static final long SHARED_SCHEMA_LOCK = 481026L;
    private static final long SECTION_LOCK_RANGE = 1_000_000_000L;
    private static final long ADMIN_RECOVERY_LOCK = SHARED_SCHEMA_LOCK + SECTION_LOCK_RANGE + 1;
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

    public Set<String> require(String actor, String permission) {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
        if (serializesWrites()) {
            if (isRecoveryAdministrationWrite(permission)) lock(ADMIN_RECOVERY_LOCK);
            lock(lockKey(permission));
        }
        RequestAuthorizationContext requestContext = currentAuthorization(actor);
        var held =
                requestContext != null && requestContext.previewActive()
                        ? new HashSet<>(requestContext.effectiveAuthorities())
                        : new HashSet<>(
                                jdbc.sql(
                                                "SELECT a.authority FROM platform_effective_authority a JOIN platform_user u ON u.id=a.user_id WHERE u.username=:name AND u.enabled")
                                        .param("name", actor)
                                        .query(String.class)
                                        .list());
        if (!held.contains(permission))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        return held;
    }

    /**
     * The advisory lock only orders writers. Outside a transaction it would be released at once
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
        jdbc.sql("SELECT pg_advisory_xact_lock(:lockKey)")
                .param("lockKey", key)
                .query(
                        rs -> {
                            rs.next();
                            return 0;
                        });
    }

    /**
     * Serialize only writes that share a consistency boundary. Settings and module lifecycle keep
     * the historical lock because profile/schema writes and the module graph use it as their first
     * lock. Other permission domains get stable advisory buckets so unrelated admin writes can
     * normally proceed concurrently across instances.
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
                targetRole == null
                        ? jdbc.sql(
                                        "SELECT EXISTS(SELECT 1 FROM platform_visible_users(:actor,:permission) WHERE user_id=:id)")
                                .param("actor", actor)
                                .param("permission", permission)
                                .param("id", target)
                                .query(Boolean.class)
                                .single()
                        : jdbc.sql(
                                        "SELECT EXISTS(SELECT 1 FROM platform_role_preview_visible_users(:actor,:permission,:targetRoleId,:targetRoleVersion) WHERE user_id=:id)")
                                .param("actor", actor)
                                .param("permission", permission)
                                .param("targetRoleId", targetRole)
                                .param("targetRoleVersion", targetVersion)
                                .param("id", target)
                                .query(Boolean.class)
                                .single();
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }

    public void requireGlobal(String actor, String permission) {
        if (!hasGlobal(actor, permission))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }

    public boolean hasGlobal(String actor, String permission) {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
        RequestAuthorizationContext requestContext = currentAuthorization(actor);
        if (requestContext != null && requestContext.previewActive())
            return requestContext.effectiveGlobalAuthorities().contains(permission);
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_global_authority a "
                                + "JOIN platform_user u ON u.id=a.user_id "
                                + "WHERE u.username=:actor AND u.enabled AND a.authority=:permission)")
                .param("actor", actor)
                .param("permission", permission)
                .query(Boolean.class)
                .single();
    }

    private static RequestAuthorizationContext currentAuthorization(String actor) {
        AuthenticationRequestContext.Snapshot snapshot = AuthenticationRequestContext.current();
        if (snapshot == null
                || snapshot.authorization() == null
                || !actor.equals(snapshot.principalName())) return null;
        return snapshot.authorization();
    }
}
