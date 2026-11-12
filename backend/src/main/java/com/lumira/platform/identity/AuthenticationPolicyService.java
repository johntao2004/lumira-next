package com.lumira.platform.identity;

import com.lumira.platform.common.AuthenticationCapabilityContributor;
import com.lumira.platform.common.GlobalAuthenticationPolicyPort;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.common.RoleAuthenticationPolicyPort;
import com.lumira.platform.identity.AdmissionCredentialProjection.CredentialEpoch;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.identity.api.PasskeyPolicyPort;
import com.lumira.platform.identity.api.SecondFactorProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the real account policy from shared host and module ports on every authentication
 * boundary.
 */
@Service
public class AuthenticationPolicyService implements EffectiveAuthenticationPolicyPort {
    public record AuthenticatorStatus(
            boolean available, Boolean configured, Integer recoveryCodesRemaining) {}

    public record AccountView(
            List<PrimaryMethod> enabledMethods,
            List<PrimaryMethod> usableMethods,
            boolean mfaRequired,
            List<RequirementSource> requirementSources,
            AuthenticatorStatus authenticator,
            String authenticationRevision) {}

    public record PublicMethods(
            List<PrimaryMethod> methods,
            Registration registration,
            Capabilities capabilities,
            Captcha captcha) {}

    public record Registration(
            boolean supported, boolean available, List<String> channels, String path) {}

    public record Capabilities(
            boolean loginCode, boolean wechat, boolean captcha, boolean rolePreview) {}

    public record Captcha(
            boolean required, String type, List<String> actions, List<String> alwaysActions) {}

    private final JdbcClient jdbc;
    private final GlobalAuthenticationPolicyPort global;
    private final RoleAuthenticationPolicyPort roles;
    private final PasskeyPolicyPort passkeys;
    private final PluginExtensionsPort plugins;
    private final ObjectProvider<AuthenticationCapabilityContributor> capabilityContributors;

    public AuthenticationPolicyService(
            JdbcClient jdbc,
            GlobalAuthenticationPolicyPort global,
            RoleAuthenticationPolicyPort roles,
            PasskeyPolicyPort passkeys,
            PluginExtensionsPort plugins,
            ObjectProvider<AuthenticationCapabilityContributor> capabilityContributors) {
        this.jdbc = jdbc;
        this.global = global;
        this.roles = roles;
        this.passkeys = passkeys;
        this.plugins = plugins;
        this.capabilityContributors = capabilityContributors;
    }

    record AccountRequirement(UUID version, boolean selfEnabled, boolean recoveryPending) {}

    private static String buildUnifiedAdmissionQuery(
            String actorFilter, boolean includeGlobalAuthorities) {
        return buildUnifiedAdmissionQuery(actorFilter, includeGlobalAuthorities, false);
    }

    private static String buildUnifiedAdmissionQuery(
            String actorFilter,
            boolean includeGlobalAuthorities,
            boolean includeNonePreviewSession) {
        String previewCte =
                includeNonePreviewSession
                        ? """
                        , preview_ctx AS MATERIALIZED (
                            SELECT trim(c.session_primary_id) AS session_primary_id, c.revision
                              FROM actor a
                              JOIN spring_session s
                                ON s.session_id = CAST(:session AS character(36))
                               AND s.principal_name = :name
                               AND s.expiry_time > (extract(epoch FROM clock_timestamp()) * 1000)::bigint
                              JOIN platform_role_preview_context c
                                ON c.session_primary_id = s.primary_id
                               AND c.actor_id = a.id
                               AND c.state = 'NONE'
                               FOR SHARE OF c
                        )"""
                        : "";
        String base =
                """
                WITH actor AS MATERIALIZED (
                    SELECT u.id, u.primary_credential_revision, u.factor_credential_revision,
                           (u.password_hash IS NOT NULL) AS password_present,
                           EXISTS(SELECT 1 FROM platform_passkey_credential p WHERE p.user_id=u.id) AS passkey_present
                      FROM platform_user u
                     WHERE %s AND u.enabled AND u.deleted_at IS NULL
                ),
                actor_roles AS NOT MATERIALIZED (
                    SELECT r.id, r.version, r.data_scope
                      FROM actor a
                      JOIN platform_user_role ur ON ur.user_id=a.id
                      JOIN platform_role r ON r.id=ur.role_id AND r.enabled
                )%s
                SELECT 0 AS section,
                       a.id AS c_uuid1, a.primary_credential_revision AS c_uuid2, a.factor_credential_revision AS c_uuid3,
                       NULL::bigint AS c_long1, NULL::bigint AS c_long2,
                       a.password_present AS c_bool1, a.passkey_present AS c_bool2,
                       NULL::text AS c_text1, NULL::text AS c_text2, NULL::text AS c_text3,
                       NULL::text[] AS c_text_array
                  FROM actor a
                UNION ALL
                SELECT 1 AS section,
                       p.revision AS c_uuid1, NULL::uuid AS c_uuid2, NULL::uuid AS c_uuid3,
                       p.version AS c_long1, NULL::bigint AS c_long2,
                       p.mfa_required AS c_bool1, NULL::boolean AS c_bool2,
                       NULL::text AS c_text1, NULL::text AS c_text2, NULL::text AS c_text3,
                       p.enabled_methods AS c_text_array
                  FROM actor a
                  JOIN platform_global_authentication_policy p ON p.id=1
                UNION ALL
                SELECT 2 AS section,
                       r.id AS c_uuid1, p.revision AS c_uuid2, NULL::uuid AS c_uuid3,
                       r.version AS c_long1, p.version AS c_long2,
                       NULL::boolean AS c_bool1, NULL::boolean AS c_bool2,
                       p.login_mode AS c_text1, p.mfa_mode AS c_text2, p.captcha_mode AS c_text3,
                       p.methods AS c_text_array
                  FROM actor_roles r
                  LEFT JOIN platform_role_authentication_policy p ON p.role_id=r.id
                UNION ALL
                SELECT 3 AS section,
                       req.requirement_version AS c_uuid1, NULL::uuid AS c_uuid2, NULL::uuid AS c_uuid3,
                       NULL::bigint AS c_long1, NULL::bigint AS c_long2,
                       req.self_enabled AS c_bool1, req.recovery_pending AS c_bool2,
                       NULL::text AS c_text1, NULL::text AS c_text2, NULL::text AS c_text3,
                       NULL::text[] AS c_text_array
                  FROM actor a
                  JOIN platform_second_factor_requirement req ON req.user_id=a.id
                UNION ALL
                SELECT 4 AS section,
                       a.primary_credential_revision AS c_uuid1, NULL::uuid AS c_uuid2, NULL::uuid AS c_uuid3,
                       e.version AS c_long1, p.version AS c_long2,
                       (e.verified_at IS NOT NULL) AS c_bool1, (p.verified_at IS NOT NULL) AS c_bool2,
                       e.email AS c_text1, p.phone AS c_text2, NULL::text AS c_text3,
                       NULL::text[] AS c_text_array
                  FROM actor a
                  LEFT JOIN platform_account_email e ON e.user_id=a.id
                  LEFT JOIN platform_account_phone p ON p.user_id=a.id
                UNION ALL
                SELECT 5 AS section,
                       NULL::uuid AS c_uuid1, NULL::uuid AS c_uuid2, NULL::uuid AS c_uuid3,
                       w.version AS c_long1, NULL::bigint AS c_long2,
                       NULL::boolean AS c_bool1, NULL::boolean AS c_bool2,
                       w.app_id AS c_text1, w.open_id_digest AS c_text2, w.nickname AS c_text3,
                       NULL::text[] AS c_text_array
                  FROM actor a
                  JOIN platform_wechat_binding w ON w.user_id=a.id
                UNION ALL
                SELECT 6 AS section,
                       c.revision AS c_uuid1, NULL::uuid AS c_uuid2, NULL::uuid AS c_uuid3,
                       c.version AS c_long1, NULL::bigint AS c_long2,
                       c.enabled AS c_bool1, NULL::boolean AS c_bool2,
                       c.challenge_type AS c_text1, NULL::text AS c_text2, NULL::text AS c_text3,
                       NULL::text[] AS c_text_array
                  FROM actor a
                  JOIN platform_captcha_settings c ON c.id=1
                """
                        .formatted(actorFilter, previewCte);
        StringBuilder query = new StringBuilder(base);
        if (includeGlobalAuthorities) {
            query.append(
                    """
                    UNION ALL
                    SELECT 7 AS section,
                           NULL::uuid AS c_uuid1, NULL::uuid AS c_uuid2, NULL::uuid AS c_uuid3,
                           NULL::bigint AS c_long1, NULL::bigint AS c_long2,
                           NULL::boolean AS c_bool1, NULL::boolean AS c_bool2,
                           pa.authority AS c_text1, NULL::text AS c_text2, NULL::text AS c_text3,
                           NULL::text[] AS c_text_array
                      FROM actor a
                      JOIN platform_authority pa ON pa.user_id=a.id
                     WHERE pa.authority NOT LIKE 'platform:__v50:%'
                       AND NOT EXISTS (SELECT 1 FROM platform_permission_retired_authority ret WHERE ret.authority=pa.authority)
                    UNION ALL
                    SELECT 8 AS section,
                           r.id AS c_uuid1, NULL::uuid AS c_uuid2, NULL::uuid AS c_uuid3,
                           NULL::bigint AS c_long1, NULL::bigint AS c_long2,
                           ((r.data_scope)::text = 'ALL'::text) AS c_bool1, NULL::boolean AS c_bool2,
                           ra.authority AS c_text1, NULL::text AS c_text2, NULL::text AS c_text3,
                           NULL::text[] AS c_text_array
                      FROM actor_roles r
                      JOIN platform_role_authority ra ON ra.role_id=r.id
                     WHERE ra.authority NOT LIKE 'platform:__v50:%'
                       AND NOT EXISTS (SELECT 1 FROM platform_permission_retired_authority ret WHERE ret.authority=ra.authority)
                    """);
        }
        if (includeNonePreviewSession) {
            query.append(
                    """
                    UNION ALL
                    SELECT 9 AS section,
                           pc.revision AS c_uuid1, NULL::uuid AS c_uuid2, NULL::uuid AS c_uuid3,
                           NULL::bigint AS c_long1, NULL::bigint AS c_long2,
                           NULL::boolean AS c_bool1, NULL::boolean AS c_bool2,
                           pc.session_primary_id AS c_text1, NULL::text AS c_text2, NULL::text AS c_text3,
                           NULL::text[] AS c_text_array
                      FROM preview_ctx pc
                    """);
        }
        return query.toString();
    }

    private static final String UNIFIED_ADMISSION_QUERY =
            buildUnifiedAdmissionQuery("u.id = :id", true);

    private static final String UNIFIED_ADMISSION_BY_NAME_QUERY =
            buildUnifiedAdmissionQuery("u.username = :name", true);

    private static final String UNIFIED_SESSION_ADMISSION_BY_NAME_QUERY =
            buildUnifiedAdmissionQuery("u.username = :name", true, true);

    private volatile Boolean unifiedSchemaAvailable;

    private boolean hasUnifiedAdmissionSchema() {
        Boolean cached = unifiedSchemaAvailable;
        if (cached != null) return cached;
        if (jdbc == null) return false;
        boolean available =
                Boolean.TRUE.equals(
                        jdbc.sql(
                                        """
                                        SELECT (
                                            (SELECT count(*)
                                               FROM pg_class c
                                               JOIN pg_namespace n ON n.oid = c.relnamespace
                                              WHERE n.nspname = current_schema()
                                                AND c.relname IN (
                                                    'platform_global_authentication_policy',
                                                    'platform_role_authentication_policy',
                                                    'platform_second_factor_requirement',
                                                    'platform_account_email',
                                                    'platform_account_phone',
                                                    'platform_wechat_binding',
                                                    'platform_captcha_settings',
                                                    'platform_passkey_credential',
                                                    'platform_authority',
                                                    'platform_user_role',
                                                    'platform_role',
                                                    'platform_role_authority',
                                                    'platform_permission_retired_authority'
                                                )) = 13
                                            AND COALESCE((
                                                SELECT c.relkind = 'v'
                                                  FROM pg_class c
                                                  JOIN pg_namespace n ON n.oid = c.relnamespace
                                                 WHERE n.nspname = current_schema()
                                                   AND c.relname = 'platform_effective_authority'
                                            ), false)
                                        )
                                        """)
                                .query(Boolean.class)
                                .optional()
                                .orElse(false));
        unifiedSchemaAvailable = available;
        return available;
    }

    private volatile Boolean unifiedSessionSchemaAvailable;

    private boolean hasUnifiedSessionTables() {
        Boolean cached = unifiedSessionSchemaAvailable;
        if (cached != null) return cached;
        if (jdbc == null) return false;
        boolean available =
                Boolean.TRUE.equals(
                        jdbc.sql(
                                        """
                                        SELECT (
                                            SELECT count(*)
                                              FROM pg_class c
                                              JOIN pg_namespace n ON n.oid = c.relnamespace
                                             WHERE n.nspname = current_schema()
                                               AND c.relname IN ('spring_session', 'platform_role_preview_context')
                                        ) = 2
                                        """)
                                .query(Boolean.class)
                                .optional()
                                .orElse(false));
        unifiedSessionSchemaAvailable = available;
        return available;
    }

    @Override
    @Transactional(readOnly = true)
    public EffectivePolicy resolve(UUID userId) {
        if (userId == null) throw new IllegalArgumentException("userId");
        CredentialEpoch credentialEpoch =
                AdmissionCredentialProjection.consume(userId)
                        .orElseGet(() -> readCredentialEpoch(userId));

        if (AdmissionCredentialProjection.isActive(userId) && supportsUnifiedAdmission()) {
            try {
                return resolveUnifiedAdmission(userId, credentialEpoch);
            } catch (RuntimeException fallback) {
                // Failsafe: on any unexpected schema or mapping failure, fall back to sequential
                // reads
            }
        }

        return resolveFallback(userId, credentialEpoch);
    }

    public boolean supportsUnifiedAdmission() {
        return global != null
                && roles != null
                && global.supportsUnifiedDatabaseProjection()
                && roles.supportsUnifiedDatabaseProjection()
                && hasUnifiedAdmissionSchema();
    }

    boolean supportsUnifiedSessionAdmission() {
        return supportsUnifiedAdmission()
                && hasUnifiedSessionTables()
                && TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }

    @FunctionalInterface
    public interface AdmissionConsumer<T> {
        T accept(
                UUID userId,
                CredentialEpoch credentialEpoch,
                EffectivePolicy policy,
                Set<String> effectiveAuthorities,
                Set<String> globalAuthorities);
    }

    @FunctionalInterface
    interface SessionAdmissionConsumer<T> {
        T accept(
                UUID userId,
                CredentialEpoch credentialEpoch,
                EffectivePolicy policy,
                Set<String> effectiveAuthorities,
                Set<String> globalAuthorities,
                String nonePreviewSessionPrimaryId,
                UUID nonePreviewRevision);
    }

    public record AdmissionResult(
            UUID userId,
            CredentialEpoch credentialEpoch,
            EffectivePolicy policy,
            Set<String> effectiveAuthorities,
            Set<String> globalAuthorities) {}

    private static Set<PrimaryMethod> parsePrimaryMethods(Array array) throws SQLException {
        var methods = EnumSet.noneOf(PrimaryMethod.class);
        if (array != null) {
            try {
                Object raw = array.getArray();
                if (raw instanceof Object[] items) {
                    for (Object item : items) {
                        if (item != null) {
                            methods.add(PrimaryMethod.valueOf(item.toString()));
                        }
                    }
                }
            } finally {
                array.free();
            }
        }
        return methods;
    }

    private static final class UnifiedProjectionRowCollector implements RowCallbackHandler {
        private final boolean readGlobal;
        UUID userId;
        CredentialEpoch credentialEpoch;
        GlobalAuthenticationPolicyPort.Policy globalPolicy;
        final List<RoleAuthenticationPolicyPort.RolePolicy> assignedRoles = new ArrayList<>();
        AccountRequirement personal;
        AdmissionCredentialProjection.LoginCodeFacts loginCode;
        AdmissionCredentialProjection.WechatFacts wechat;
        UUID captchaRevision;
        final Set<String> effectiveAuthorities = new HashSet<>();
        final Set<String> globalAuthorities = new HashSet<>();
        String nonePreviewSessionPrimaryId;
        UUID nonePreviewRevision;

        UnifiedProjectionRowCollector(boolean readGlobal) {
            this.readGlobal = readGlobal;
        }

        @Override
        public void processRow(ResultSet rs) throws SQLException {
            int section = rs.getInt("section");
            switch (section) {
                case 0 -> {
                    userId = rs.getObject("c_uuid1", UUID.class);
                    UUID primaryRevision = rs.getObject("c_uuid2", UUID.class);
                    UUID factorRevision = rs.getObject("c_uuid3", UUID.class);
                    boolean pwd = rs.getBoolean("c_bool1");
                    boolean psk = rs.getBoolean("c_bool2");
                    credentialEpoch =
                            new CredentialEpoch(primaryRevision, factorRevision, pwd, psk);
                }
                case 1 -> {
                    long version = rs.getLong("c_long1");
                    UUID revision = rs.getObject("c_uuid1", UUID.class);
                    boolean mfaRequired = rs.getBoolean("c_bool1");
                    Set<PrimaryMethod> methods = parsePrimaryMethods(rs.getArray("c_text_array"));
                    globalPolicy =
                            new GlobalAuthenticationPolicyPort.Policy(
                                    version, revision, methods, mfaRequired);
                }
                case 2 -> {
                    UUID roleId = rs.getObject("c_uuid1", UUID.class);
                    long roleVersion = rs.getLong("c_long1");
                    long policyVersion = rs.getLong("c_long2");
                    if (rs.wasNull()) policyVersion = 0;
                    UUID revision = rs.getObject("c_uuid2", UUID.class);
                    if (revision == null) {
                        revision =
                                UUID.nameUUIDFromBytes(
                                        ("role-auth:" + roleId + ":" + roleVersion)
                                                .getBytes(StandardCharsets.UTF_8));
                    }
                    String loginMode = rs.getString("c_text1");
                    String mfaMode = rs.getString("c_text2");
                    String captchaMode = rs.getString("c_text3");
                    Set<PrimaryMethod> methods = parsePrimaryMethods(rs.getArray("c_text_array"));
                    assignedRoles.add(
                            new RoleAuthenticationPolicyPort.RolePolicy(
                                    roleId,
                                    roleVersion,
                                    policyVersion,
                                    revision,
                                    loginMode == null
                                            ? RoleAuthenticationPolicyPort.LoginMode.INHERIT
                                            : RoleAuthenticationPolicyPort.LoginMode.valueOf(
                                                    loginMode),
                                    methods,
                                    mfaMode == null
                                            ? RoleAuthenticationPolicyPort.MfaMode.INHERIT
                                            : RoleAuthenticationPolicyPort.MfaMode.valueOf(mfaMode),
                                    captchaMode == null
                                            ? RoleAuthenticationPolicyPort.CaptchaMode.INHERIT
                                            : RoleAuthenticationPolicyPort.CaptchaMode.valueOf(
                                                    captchaMode)));
                }
                case 3 -> {
                    UUID reqVersion = rs.getObject("c_uuid1", UUID.class);
                    if (reqVersion != null) {
                        personal =
                                new AccountRequirement(
                                        reqVersion,
                                        rs.getBoolean("c_bool1"),
                                        rs.getBoolean("c_bool2"));
                    }
                }
                case 4 -> {
                    UUID primaryRevision = rs.getObject("c_uuid1", UUID.class);
                    String email = rs.getString("c_text1");
                    boolean emailVerified = rs.getBoolean("c_bool1");
                    long ev = rs.getLong("c_long1");
                    Long emailVersion = rs.wasNull() ? null : ev;
                    String phone = rs.getString("c_text2");
                    boolean phoneVerified = rs.getBoolean("c_bool2");
                    long pv = rs.getLong("c_long2");
                    Long phoneVersion = rs.wasNull() ? null : pv;
                    loginCode =
                            new AdmissionCredentialProjection.LoginCodeFacts(
                                    primaryRevision,
                                    email,
                                    emailVerified,
                                    emailVersion,
                                    phone,
                                    phoneVerified,
                                    phoneVersion);
                }
                case 5 -> {
                    long version = rs.getLong("c_long1");
                    String appId = rs.getString("c_text1");
                    String digest = rs.getString("c_text2");
                    String nickname = rs.getString("c_text3");
                    wechat =
                            new AdmissionCredentialProjection.WechatFacts(
                                    version, appId, digest, nickname);
                }
                case 6 -> {
                    captchaRevision = rs.getObject("c_uuid1", UUID.class);
                }
                case 7 -> {
                    String auth = rs.getString("c_text1");
                    if (auth != null) {
                        effectiveAuthorities.add(auth);
                        if (readGlobal) {
                            globalAuthorities.add(auth);
                        }
                    }
                }
                case 8 -> {
                    String auth = rs.getString("c_text1");
                    if (auth != null) {
                        effectiveAuthorities.add(auth);
                        if (readGlobal && rs.getBoolean("c_bool1")) {
                            globalAuthorities.add(auth);
                        }
                    }
                }
                case 9 -> {
                    nonePreviewRevision = rs.getObject("c_uuid1", UUID.class);
                    nonePreviewSessionPrimaryId = rs.getString("c_text1");
                }
            }
        }
    }

    private record UnifiedAdmissionData(
            UUID userId,
            CredentialEpoch epoch,
            EffectivePolicy policy,
            Set<String> effectiveAuthorities,
            Set<String> globalAuthorities) {}

    private UnifiedAdmissionData buildOutcome(
            UUID userId, CredentialEpoch credentialEpoch, UnifiedProjectionRowCollector collector) {
        collector.assignedRoles.sort(
                Comparator.comparing(RoleAuthenticationPolicyPort.RolePolicy::roleId));
        var wechatFacts =
                collector.wechat != null
                        ? collector.wechat
                        : new AdmissionCredentialProjection.WechatFacts(0, null, null, null);
        var effective = Set.copyOf(collector.effectiveAuthorities);
        var global = Set.copyOf(collector.globalAuthorities);
        AdmissionCredentialProjection.setAdmissionFacts(
                userId, collector.loginCode, wechatFacts, collector.captchaRevision);
        AdmissionCredentialProjection.setGlobalAuthorities(userId, global);
        AdmissionCredentialProjection.setEffectiveAuthorities(userId, effective);

        EffectivePolicy policy =
                assemblePolicy(
                        userId,
                        credentialEpoch,
                        collector.globalPolicy,
                        collector.assignedRoles,
                        collector.personal);
        return new UnifiedAdmissionData(userId, credentialEpoch, policy, effective, global);
    }

    private UnifiedProjectionRowCollector queryUnified(
            String sql, String paramName, Object paramValue, boolean readGlobal) {
        var collector = new UnifiedProjectionRowCollector(readGlobal);
        jdbc.sql(sql).param(paramName, paramValue).query(collector);
        return collector;
    }

    private <T> T executeAdmission(
            UnifiedProjectionRowCollector collector, SessionAdmissionConsumer<T> consumer) {
        UUID userId = collector.userId;
        CredentialEpoch credentialEpoch = collector.credentialEpoch;
        if (userId == null || credentialEpoch == null) {
            return null;
        }

        if (collector.globalPolicy == null) {
            throw new IllegalStateException("Global authentication policy not found");
        }

        var finalEffective = Set.copyOf(collector.effectiveAuthorities);
        var finalGlobal = Set.copyOf(collector.globalAuthorities);

        return AdmissionCredentialProjection.within(
                userId,
                credentialEpoch,
                finalGlobal,
                finalEffective,
                () -> {
                    UnifiedAdmissionData data = buildOutcome(userId, credentialEpoch, collector);
                    return consumer.accept(
                            userId,
                            credentialEpoch,
                            data.policy(),
                            finalEffective,
                            finalGlobal,
                            collector.nonePreviewSessionPrimaryId,
                            collector.nonePreviewRevision);
                });
    }

    @Transactional(readOnly = true)
    public <T> T admit(String username, AdmissionConsumer<T> consumer) {
        if (!supportsUnifiedAdmission() || username == null) {
            return null;
        }

        var collector = queryUnified(UNIFIED_ADMISSION_BY_NAME_QUERY, "name", username, true);
        return executeAdmission(
                collector,
                (userId, epoch, policy, eff, glob, noneSessionPrimaryId, noneRevision) ->
                        consumer.accept(userId, epoch, policy, eff, glob));
    }

    <T> T admitWithSession(
            String username, String sessionId, SessionAdmissionConsumer<T> consumer) {
        if (!supportsUnifiedSessionAdmission() || username == null || sessionId == null) {
            return null;
        }

        var collector = new UnifiedProjectionRowCollector(true);
        jdbc.sql(UNIFIED_SESSION_ADMISSION_BY_NAME_QUERY)
                .param("name", username)
                .param("session", sessionId)
                .query(collector);
        return executeAdmission(collector, consumer);
    }

    @Transactional(readOnly = true)
    public AdmissionResult resolveAdmission(String username) {
        return admit(username, AdmissionResult::new);
    }

    private EffectivePolicy resolveUnifiedAdmission(UUID userId, CredentialEpoch credentialEpoch) {
        var collector = queryUnified(UNIFIED_ADMISSION_QUERY, "id", userId, true);
        if (collector.userId == null || collector.globalPolicy == null) {
            return resolveFallback(userId, credentialEpoch);
        }
        return buildOutcome(userId, credentialEpoch, collector).policy();
    }

    private EffectivePolicy resolveFallback(UUID userId, CredentialEpoch credentialEpoch) {
        var globalPolicy = global.current();
        List<RoleAuthenticationPolicyPort.RolePolicy> assignedRoles = roles.activeForUser(userId);
        AccountRequirement personal =
                jdbc.sql(
                                """
                                SELECT requirement_version,self_enabled,recovery_pending
                                  FROM platform_second_factor_requirement WHERE user_id=:id
                                """)
                        .param("id", userId)
                        .query(
                                (rs, row) ->
                                        new AccountRequirement(
                                                rs.getObject("requirement_version", UUID.class),
                                                rs.getBoolean("self_enabled"),
                                                rs.getBoolean("recovery_pending")))
                        .optional()
                        .orElse(null);
        return assemblePolicy(userId, credentialEpoch, globalPolicy, assignedRoles, personal);
    }

    private EffectivePolicy assemblePolicy(
            UUID userId,
            CredentialEpoch credentialEpoch,
            GlobalAuthenticationPolicyPort.Policy globalPolicy,
            List<RoleAuthenticationPolicyPort.RolePolicy> assignedRoles,
            AccountRequirement personal) {
        EnumSet<PrimaryMethod> allowed =
                globalPolicy.enabledMethods().isEmpty()
                        ? EnumSet.noneOf(PrimaryMethod.class)
                        : EnumSet.copyOf(globalPolicy.enabledMethods());
        boolean roleRequiresMfa = false;
        boolean captchaRequired = false;
        var sources = new ArrayList<RequirementSource>();
        for (RoleAuthenticationPolicyPort.RolePolicy role : assignedRoles) {
            if (role.loginMode() == RoleAuthenticationPolicyPort.LoginMode.RESTRICT)
                allowed.retainAll(role.methods());
            if (role.mfaMode() == RoleAuthenticationPolicyPort.MfaMode.REQUIRED) {
                roleRequiresMfa = true;
                sources.add(new RequirementSource("ROLE", role.roleId()));
            }
            captchaRequired |=
                    role.captchaMode() == RoleAuthenticationPolicyPort.CaptchaMode.ALWAYS;
        }
        if (globalPolicy.mfaRequired()) sources.add(new RequirementSource("GLOBAL", null));

        if (personal != null && personal.selfEnabled())
            sources.add(new RequirementSource("SELF_ENABLED", null));
        if (personal != null && personal.recoveryPending())
            sources.add(new RequirementSource("RECOVERY_PENDING", null));

        EnumSet<PrimaryMethod> usable = EnumSet.noneOf(PrimaryMethod.class);
        if (allowed.contains(PrimaryMethod.PASSWORD) && credentialEpoch.passwordPresent())
            usable.add(PrimaryMethod.PASSWORD);
        if (allowed.contains(PrimaryMethod.PASSKEY) && credentialEpoch.passkeyPresent()) {
            PasskeyPolicyPort.Policy passkeyPolicy = passkeys.currentPolicy();
            if (Boolean.TRUE.equals(passkeyPolicy.enabled())
                    && Boolean.TRUE.equals(passkeyPolicy.passwordlessEnabled())) {
                usable.add(PrimaryMethod.PASSKEY);
            }
        }
        var contributedRevisions = new TreeMap<String, String>();
        for (var contributor : capabilityContributors.orderedStream().toList()) {
            var account = contributor.accountMethods(userId);
            for (PrimaryMethod method : account.usableMethods())
                if (allowed.contains(method)) usable.add(method);
            contributedRevisions.put(contributor.id(), account.authenticationRevision());
        }

        boolean mfaRequired =
                globalPolicy.mfaRequired()
                        || roleRequiresMfa
                        || personal != null
                                && (personal.selfEnabled() || personal.recoveryPending());
        UUID revision =
                revision(
                        userId,
                        globalPolicy.revision(),
                        assignedRoles,
                        personal,
                        credentialEpoch,
                        allowed,
                        usable,
                        contributedRevisions);
        return new EffectivePolicy(
                revision,
                allowed,
                usable,
                mfaRequired,
                sources,
                captchaRequired,
                credentialEpoch.primary(),
                credentialEpoch.factor());
    }

    private CredentialEpoch readCredentialEpoch(UUID userId) {
        return jdbc.sql(
                        """
                        SELECT u.primary_credential_revision,u.factor_credential_revision,
                               (u.password_hash IS NOT NULL) AS password_present,
                               EXISTS(SELECT 1 FROM platform_passkey_credential p WHERE p.user_id=u.id) AS passkey_present
                          FROM platform_user u WHERE u.id=:id AND u.enabled AND u.deleted_at IS NULL
                        """)
                .param("id", userId)
                .query(
                        (rs, row) ->
                                new CredentialEpoch(
                                        rs.getObject("primary_credential_revision", UUID.class),
                                        rs.getObject("factor_credential_revision", UUID.class),
                                        rs.getBoolean("password_present"),
                                        rs.getBoolean("passkey_present")))
                .optional()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED, "authenticationRequired"));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean requiredByPolicyExceptSelf(UUID userId) {
        boolean globalRequired = global.current().mfaRequired();
        if (globalRequired) return true;
        for (var role : roles.activeForUser(userId))
            if (role.mfaMode() == RoleAuthenticationPolicyPort.MfaMode.REQUIRED) return true;
        var status =
                jdbc.sql(
                                "SELECT recovery_pending FROM platform_second_factor_requirement WHERE user_id=:id")
                        .param("id", userId)
                        .query((rs, rowNum) -> rs.getBoolean(1))
                        .optional();
        return status.orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean secondFactorAvailable() {
        long availableProviders =
                plugins
                        .extensions(
                                SecondFactorProvider.class,
                                p ->
                                        p.id().equals("mfa")
                                                && p.available()
                                                && p.supportsRestrictedEnrollmentAndRecovery())
                        .stream()
                        .filter(Boolean.TRUE::equals)
                        .count();
        return availableProviders == 1;
    }

    @Transactional(readOnly = true)
    public AccountView accountView(UUID userId) {
        EffectivePolicy effective = resolve(userId);
        var auth = authenticatorStatus(userId);
        return new AccountView(
                effective.allowedMethods().stream().sorted().toList(),
                effective.usableMethods().stream().sorted().toList(),
                effective.mfaRequired(),
                effective.requirementSources(),
                auth,
                effective.revision().toString());
    }

    @Transactional(readOnly = true)
    public AuthenticatorStatus authenticatorStatus(UUID userId) {
        List<AuthenticatorStatus> availableProviders =
                plugins
                        .extensions(
                                SecondFactorProvider.class,
                                provider -> {
                                    if (!provider.id().equals("mfa") || !provider.available())
                                        return new AuthenticatorStatus(false, null, null);
                                    var status = provider.status(userId);
                                    return status == null
                                            ? new AuthenticatorStatus(true, null, null)
                                            : new AuthenticatorStatus(
                                                    true,
                                                    status.configured(),
                                                    status.recoveryCodesRemaining());
                                })
                        .stream()
                        .filter(AuthenticatorStatus::available)
                        .toList();
        return availableProviders.size() == 1
                ? availableProviders.getFirst()
                : new AuthenticatorStatus(false, null, null);
    }

    @Transactional(readOnly = true)
    public PublicMethods publicMethods() {
        var globalPolicy = global.current();
        var methods = new ArrayList<PrimaryMethod>();
        if (globalPolicy.enabledMethods().contains(PrimaryMethod.PASSWORD))
            methods.add(PrimaryMethod.PASSWORD);
        PasskeyPolicyPort.Policy passkeyPolicy = passkeys.currentPolicy();
        if (globalPolicy.enabledMethods().contains(PrimaryMethod.PASSKEY)
                && Boolean.TRUE.equals(passkeyPolicy.enabled())
                && Boolean.TRUE.equals(passkeyPolicy.passwordlessEnabled()))
            methods.add(PrimaryMethod.PASSKEY);
        boolean loginCode = false;
        boolean wechat = false;
        boolean captchaSupported = false;
        boolean rolePreview = false;
        boolean captchaRequired = false;
        String captchaType = null;
        var captchaActions = new LinkedHashSet<String>();
        var captchaAlwaysActions = new LinkedHashSet<String>();
        boolean registrationSupported = false;
        boolean registrationAvailable = false;
        var registrationChannels = new LinkedHashSet<String>();
        String registrationPath = null;
        for (var contributor : capabilityContributors.orderedStream().toList()) {
            var addition = contributor.authenticationCapabilities();
            for (PrimaryMethod method : addition.methods())
                if (globalPolicy.enabledMethods().contains(method) && !methods.contains(method))
                    methods.add(method);
            loginCode |= addition.loginCode();
            wechat |= addition.wechat();
            captchaSupported |= addition.captcha();
            rolePreview |= addition.rolePreview();
            captchaRequired |= addition.captchaPolicy().required();
            if (captchaType == null) captchaType = addition.captchaPolicy().type();
            captchaActions.addAll(addition.captchaPolicy().actions());
            captchaAlwaysActions.addAll(addition.captchaPolicy().alwaysActions());
            registrationSupported |= addition.registration().supported();
            registrationAvailable |= addition.registration().available();
            registrationChannels.addAll(addition.registration().channels());
            if (registrationPath == null) registrationPath = addition.registration().path();
        }
        loginCode &=
                (methods.contains(PrimaryMethod.EMAIL_OTP)
                        || methods.contains(PrimaryMethod.SMS_OTP));
        wechat &= methods.contains(PrimaryMethod.WECHAT);
        return new PublicMethods(
                List.copyOf(methods),
                new Registration(
                        registrationSupported,
                        registrationAvailable,
                        List.copyOf(registrationChannels),
                        registrationPath),
                new Capabilities(loginCode, wechat, captchaSupported, rolePreview),
                new Captcha(
                        captchaRequired,
                        captchaType,
                        List.copyOf(captchaActions),
                        List.copyOf(captchaAlwaysActions)));
    }

    private UUID revision(
            UUID userId,
            UUID globalRevision,
            List<RoleAuthenticationPolicyPort.RolePolicy> assignedRoles,
            Object personalRequirement,
            Object credentialEpoch,
            Set<PrimaryMethod> allowed,
            Set<PrimaryMethod> usable,
            Map<String, String> contributedRevisions) {
        StringBuilder value =
                new StringBuilder(userId.toString())
                        .append('|')
                        .append(globalRevision)
                        .append('|')
                        .append(credentialEpoch);
        for (var role : assignedRoles) {
            value.append('|')
                    .append(role.roleId())
                    .append(':')
                    .append(role.roleVersion())
                    .append(':')
                    .append(role.version())
                    .append(':')
                    .append(role.revision());
        }
        if (personalRequirement != null) value.append('|').append(personalRequirement);
        value.append('|')
                .append(allowed.stream().map(Enum::name).sorted().toList())
                .append('|')
                .append(usable.stream().map(Enum::name).sorted().toList())
                .append('|')
                .append(contributedRevisions);
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
            digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
            long high = java.nio.ByteBuffer.wrap(digest, 0, 8).getLong();
            long low = java.nio.ByteBuffer.wrap(digest, 8, 8).getLong();
            return new UUID(high, low);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }
}
