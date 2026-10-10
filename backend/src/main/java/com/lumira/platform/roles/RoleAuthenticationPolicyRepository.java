package com.lumira.platform.roles;

import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.common.RoleAuthenticationPolicyPort;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RoleAuthenticationPolicyRepository implements RoleAuthenticationPolicyPort {
    private final JdbcClient jdbc;

    public RoleAuthenticationPolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RolePolicy> activeForUser(UUID userId) {
        return jdbc.sql(
                        """
                        SELECT r.id AS role_id,r.version AS role_version,
                               p.version AS policy_version,p.revision,p.login_mode,p.methods,
                               p.mfa_mode,p.captcha_mode
                          FROM platform_user_role ur
                          JOIN platform_role r ON r.id=ur.role_id AND r.enabled
                          LEFT JOIN platform_role_authentication_policy p ON p.role_id=r.id
                         WHERE ur.user_id=:userId
                         ORDER BY r.id
                        """)
                .param("userId", userId)
                .query((rs, row) -> map(rs))
                .list();
    }

    @Override
    public RolePolicy forRole(UUID roleId) {
        return jdbc.sql(
                        """
                        SELECT r.id AS role_id,r.version AS role_version,
                               p.version AS policy_version,p.revision,p.login_mode,p.methods,
                               p.mfa_mode,p.captcha_mode
                          FROM platform_role r
                          LEFT JOIN platform_role_authentication_policy p ON p.role_id=r.id
                         WHERE r.id=:roleId
                        """)
                .param("roleId", roleId)
                .query((rs, row) -> map(rs))
                .optional()
                .orElse(null);
    }

    public long assignedCount(UUID roleId) {
        return jdbc.sql("SELECT count(*) FROM platform_user_role WHERE role_id=:roleId")
                .param("roleId", roleId)
                .query(Long.class)
                .single();
    }

    public List<UUID> assignedEnabledUsers(UUID roleId) {
        return jdbc.sql(
                        """
                        SELECT u.id FROM platform_user_role ur
                        JOIN platform_user u ON u.id=ur.user_id AND u.enabled AND u.deleted_at IS NULL
                        WHERE ur.role_id=:roleId ORDER BY u.id
                        """)
                .param("roleId", roleId)
                .query(UUID.class)
                .list();
    }

    private static RolePolicy map(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID roleId = rs.getObject("role_id", UUID.class);
        long roleVersion = rs.getLong("role_version");
        long policyVersion =
                rs.getObject("policy_version", Long.class) == null
                        ? 0
                        : rs.getLong("policy_version");
        String loginMode = rs.getString("login_mode");
        String mfaMode = rs.getString("mfa_mode");
        String captchaMode = rs.getString("captcha_mode");
        UUID revision = rs.getObject("revision", UUID.class);
        if (revision == null) {
            revision =
                    UUID.nameUUIDFromBytes(
                            ("role-auth:" + roleId + ":" + roleVersion)
                                    .getBytes(StandardCharsets.UTF_8));
        }
        Set<PrimaryMethod> methods = EnumSet.noneOf(PrimaryMethod.class);
        java.sql.Array array = rs.getArray("methods");
        if (array != null) {
            for (String method : (String[]) array.getArray())
                methods.add(PrimaryMethod.valueOf(method));
        }
        return new RolePolicy(
                roleId,
                roleVersion,
                policyVersion,
                revision,
                loginMode == null ? LoginMode.INHERIT : LoginMode.valueOf(loginMode),
                methods,
                mfaMode == null ? MfaMode.INHERIT : MfaMode.valueOf(mfaMode),
                captchaMode == null ? CaptchaMode.INHERIT : CaptchaMode.valueOf(captchaMode));
    }
}
