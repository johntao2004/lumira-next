package com.lumira.platform.identity;

import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.AuthenticationMethodAvailabilityPort;
import com.lumira.platform.identity.api.AuthenticationReachabilityPort;
import com.lumira.platform.identity.api.WechatAvailabilityPort;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Uses one indexed relational check per policy write instead of resolving each account in Java. */
@Service
public class AuthenticationReachabilityService implements AuthenticationReachabilityPort {
    private static final String BASE_QUERY =
            """
            SELECT NOT EXISTS (
                SELECT 1
                  FROM platform_user u
                 WHERE u.enabled AND u.deleted_at IS NULL
                   %s
                   AND NOT (
                       (:passwordEnabled AND u.password_hash IS NOT NULL AND btrim(u.password_hash) <> ''
                        AND NOT EXISTS (
                            SELECT 1
                              FROM platform_user_role ur
                              JOIN platform_role r ON r.id=ur.role_id AND r.enabled
                              JOIN platform_role_authentication_policy p ON p.role_id=r.id
                             WHERE ur.user_id=u.id AND p.login_mode='RESTRICT'
                               AND NOT (p.methods @> ARRAY['PASSWORD']::text[])
                        ))
                       OR (:emailOtpEnabled AND :emailOtpReady
                           AND EXISTS (SELECT 1 FROM platform_account_email e
                                        WHERE e.user_id=u.id AND e.email IS NOT NULL AND e.verified_at IS NOT NULL)
                           AND NOT EXISTS (
                                SELECT 1
                                  FROM platform_user_role ur
                                  JOIN platform_role r ON r.id=ur.role_id AND r.enabled
                                  JOIN platform_role_authentication_policy p ON p.role_id=r.id
                                 WHERE ur.user_id=u.id AND p.login_mode='RESTRICT'
                                   AND NOT (p.methods @> ARRAY['EMAIL_OTP']::text[])
                           ))
                       OR (:smsOtpEnabled AND :smsOtpReady
                           AND EXISTS (SELECT 1 FROM platform_account_phone p
                                        WHERE p.user_id=u.id AND p.phone IS NOT NULL AND p.verified_at IS NOT NULL)
                           AND NOT EXISTS (
                                SELECT 1
                                  FROM platform_user_role ur
                                  JOIN platform_role r ON r.id=ur.role_id AND r.enabled
                                  JOIN platform_role_authentication_policy p ON p.role_id=r.id
                                 WHERE ur.user_id=u.id AND p.login_mode='RESTRICT'
                                   AND NOT (p.methods @> ARRAY['SMS_OTP']::text[])
                           ))
                       OR (:wechatEnabled AND :wechatReady
                           AND EXISTS (SELECT 1 FROM platform_wechat_binding w
                                        WHERE w.user_id=u.id AND w.app_id=:wechatAppId
                                          AND w.open_id_digest IS NOT NULL)
                           AND NOT EXISTS (
                                SELECT 1
                                  FROM platform_user_role ur
                                  JOIN platform_role r ON r.id=ur.role_id AND r.enabled
                                  JOIN platform_role_authentication_policy p ON p.role_id=r.id
                                 WHERE ur.user_id=u.id AND p.login_mode='RESTRICT'
                                   AND NOT (p.methods @> ARRAY['WECHAT']::text[])
                           ))
                       OR (:passkeyEnabled AND :passkeyUsable
                           AND EXISTS (SELECT 1 FROM platform_passkey_credential c WHERE c.user_id=u.id)
                           AND NOT EXISTS (
                                SELECT 1
                                  FROM platform_user_role ur
                                  JOIN platform_role r ON r.id=ur.role_id AND r.enabled
                                  JOIN platform_role_authentication_policy p ON p.role_id=r.id
                                 WHERE ur.user_id=u.id AND p.login_mode='RESTRICT'
                                   AND NOT (p.methods @> ARRAY['PASSKEY']::text[])
                           ))
                   )
            )
            """;

    private final JdbcClient jdbc;
    private final AuthenticationMethodAvailabilityPort methodAvailability;
    private final ObjectProvider<WechatAvailabilityPort> wechatAvailability;

    public AuthenticationReachabilityService(
            JdbcClient jdbc,
            AuthenticationMethodAvailabilityPort methodAvailability,
            ObjectProvider<WechatAvailabilityPort> wechatAvailability) {
        this.jdbc = jdbc;
        this.methodAvailability = methodAvailability;
        this.wechatAvailability = wechatAvailability;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean allActiveAccountsReachable(Set<PrimaryMethod> globalMethods) {
        return reachable(null, globalMethods);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean assignedAccountsReachable(UUID roleId, Set<PrimaryMethod> globalMethods) {
        if (roleId == null) return false;
        return reachable(roleId, globalMethods);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean assignedAccountsRequireSecondFactor(UUID roleId) {
        if (roleId == null) return false;
        return jdbc.sql(
                        """
                        SELECT EXISTS (
                            SELECT 1
                              FROM platform_user u
                             WHERE u.enabled AND u.deleted_at IS NULL
                               AND EXISTS (
                                   SELECT 1 FROM platform_user_role target
                                    WHERE target.user_id=u.id AND target.role_id=:roleId)
                               AND (
                                   EXISTS (
                                       SELECT 1 FROM platform_user_role ur
                                       JOIN platform_role r ON r.id=ur.role_id AND r.enabled
                                       JOIN platform_role_authentication_policy p ON p.role_id=r.id
                                        WHERE ur.user_id=u.id AND p.mfa_mode='REQUIRED')
                                   OR EXISTS (
                                       SELECT 1 FROM platform_second_factor_requirement q
                                        WHERE q.user_id=u.id AND (q.self_enabled OR q.recovery_pending))
                               )
                        )
                        """)
                .param("roleId", roleId)
                .query(Boolean.class)
                .single();
    }

    private boolean reachable(UUID roleId, Set<PrimaryMethod> globalMethods) {
        Set<PrimaryMethod> available = methodAvailability.availableMethods();
        boolean passkeyUsable = available.contains(PrimaryMethod.PASSKEY);
        boolean emailOtpReady =
                globalMethods.contains(PrimaryMethod.EMAIL_OTP)
                        && available.contains(PrimaryMethod.EMAIL_OTP);
        boolean smsOtpReady =
                globalMethods.contains(PrimaryMethod.SMS_OTP)
                        && available.contains(PrimaryMethod.SMS_OTP);
        String wechatAppId = currentWechatAppId(globalMethods.contains(PrimaryMethod.WECHAT));
        boolean wechatReady = !wechatAppId.isEmpty();
        String scope =
                roleId == null
                        ? ""
                        : "AND EXISTS (SELECT 1 FROM platform_user_role affected WHERE affected.user_id=u.id AND affected.role_id=:roleId)";
        var query =
                jdbc.sql(BASE_QUERY.formatted(scope))
                        .param("passwordEnabled", globalMethods.contains(PrimaryMethod.PASSWORD))
                        .param("passkeyEnabled", globalMethods.contains(PrimaryMethod.PASSKEY))
                        .param("passkeyUsable", passkeyUsable)
                        .param("emailOtpEnabled", globalMethods.contains(PrimaryMethod.EMAIL_OTP))
                        .param("emailOtpReady", emailOtpReady)
                        .param("smsOtpEnabled", globalMethods.contains(PrimaryMethod.SMS_OTP))
                        .param("smsOtpReady", smsOtpReady)
                        .param("wechatEnabled", globalMethods.contains(PrimaryMethod.WECHAT))
                        .param("wechatReady", wechatReady)
                        .param("wechatAppId", wechatAppId);
        if (roleId != null) query = query.param("roleId", roleId);
        return query.query(Boolean.class).single();
    }

    private String currentWechatAppId(boolean methodEnabled) {
        if (!methodEnabled) return "";
        var provider = wechatAvailability.getIfAvailable();
        if (provider == null) return "";
        String appId = provider.currentAppId();
        return provider.ready() && appId != null && !appId.isBlank() ? appId : "";
    }
}
