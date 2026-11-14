/**
 * 装配密码认证、哈希升级与管理员引导，以带版本标识的密码编码和事务化首次改密要求保护凭据兼容及初始化。 Configures password authentication, hash
 * upgrades and administrator bootstrap, using versioned encoding and transactional first-change
 * requirements to protect credential compatibility and initialization.
 */
package com.lumira.platform.identity;

import com.lumira.platform.common.BootstrapRolePort;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class IdentityConfiguration {
    private static final String ARGON2_ID = "argon2id@lumira_v1";

    /**
     * 新密码使用 Argon2id；显式标识将参数纳入存储契约，原始 BCrypt 回退保证滚动迁移期间旧记录仍可验证。
     *
     * <p>Argon2id is the write path. The explicit id makes the parameters part of the stored
     * contract, while the raw BCrypt fallback keeps existing rows readable during a rolling
     * migration.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        var argon2 = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
        var bcrypt = new BCryptPasswordEncoder(12);
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put(ARGON2_ID, argon2);
        encoders.put("bcrypt", bcrypt);
        var delegating = new DelegatingPasswordEncoder(ARGON2_ID, encoders);
        // 现有 Lumira 记录早于 DelegatingPasswordEncoder，保存的是原始 BCrypt 哈希。
        // Existing Lumira rows predate DelegatingPasswordEncoder and contain raw BCrypt.
        delegating.setDefaultPasswordEncoderForMatches(bcrypt);
        return delegating;
    }

    /**
     * 只有输入密码已验证通过后，才升级旧哈希。 Upgrade a legacy hash only when the presented password has already
     * passed.
     */
    @Bean
    UserDetailsPasswordService userDetailsPasswordService(JdbcClient jdbc) {
        return (user, newPassword) -> {
            int updated =
                    jdbc.sql(
                                    "UPDATE platform_user SET password_hash=:password WHERE username=:username AND password_hash=:oldHash AND enabled=true")
                            .param("password", newPassword)
                            .param("username", user.getUsername())
                            .param("oldHash", user.getPassword())
                            .update();
            if (updated != 1) return user;
            if (user instanceof PasswordProofUserDetails proofUser) {
                proofUser.useEncodedPassword(newPassword);
                return proofUser;
            }
            return User.withUserDetails(user).password(newPassword).build();
        };
    }

    /**
     * 显式提供 Spring Security 登录成功后的哈希升级钩子，便于测试。 Make Spring Security's successful-login rehash hook
     * explicit and testable.
     */
    @Bean
    PasswordCredentialProof passwordCredentialProof() {
        return new PasswordCredentialProof();
    }

    @Bean
    AuthenticationProvider authenticationProvider(
            UserDetailsService users,
            PasswordEncoder encoder,
            UserDetailsPasswordService upgrades,
            PasswordCredentialProof proofs) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        provider.setUserDetailsPasswordService(upgrades);
        return new PasswordProofAuthenticationProvider(provider, proofs);
    }

    AuthenticationProvider authenticationProvider(
            UserDetailsService users,
            PasswordEncoder encoder,
            UserDetailsPasswordService upgrades) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        provider.setUserDetailsPasswordService(upgrades);
        return new PasswordProofAuthenticationProvider(provider, new PasswordCredentialProof());
    }

    @Bean
    UserDetailsService userDetailsService(JdbcClient jdbc) {
        return username -> {
            record Account(
                    UUID id, String password, boolean enabled, UUID primaryCredentialRevision) {}
            var account =
                    jdbc.sql(
                                    "SELECT id,password_hash,enabled,primary_credential_revision FROM platform_user WHERE username=:username")
                            .param("username", username)
                            .query(
                                    (rs, n) ->
                                            new Account(
                                                    rs.getObject("id", UUID.class),
                                                    rs.getString("password_hash"),
                                                    rs.getBoolean("enabled"),
                                                    rs.getObject(
                                                            "primary_credential_revision",
                                                            UUID.class)))
                            .optional()
                            .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
            var authorities =
                    com.lumira.platform.modules.ActivePermissionCatalog.retain(
                                    jdbc.sql(
                                                    "SELECT authority FROM platform_effective_authority WHERE user_id=:id")
                                            .param("id", account.id())
                                            .query(String.class)
                                            .list())
                            .stream()
                            .toList();
            var granted =
                    authorities.stream()
                            .map(
                                    org.springframework.security.core.authority
                                                    .SimpleGrantedAuthority
                                            ::new)
                            .toList();
            return new PasswordProofUserDetails(
                    username,
                    account.password(),
                    account.enabled(),
                    account.primaryCredentialRevision(),
                    granted);
        };
    }

    @Bean
    ApplicationRunner bootstrap(
            JdbcClient jdbc,
            PasswordEncoder encoder,
            TransactionTemplate tx,
            IdentityAuthenticationPolicyWriteLock policyLock,
            BootstrapRolePort administratorRoles,
            @Value("${lumira.bootstrap.password}") String password) {
        return args -> {
            if (password.isBlank()) return;
            if (password.length() < 16)
                throw new IllegalArgumentException(
                        "Bootstrap password must contain at least 16 characters");
            tx.executeWithoutResult(
                    status -> {
                        // 在并发启动的实例之间串行化初始化。
                        // Serializes initialization across concurrently starting instances.
                        jdbc.sql("SELECT pg_advisory_xact_lock(481025)")
                                .query(
                                        rs -> {
                                            rs.next();
                                            return 0;
                                        });
                        policyLock.lockForUpdate();
                        if (jdbc.sql("SELECT count(*) FROM platform_user")
                                        .query(Long.class)
                                        .single()
                                == 0) {
                            createBootstrapAdministrator(
                                    jdbc, encoder, administratorRoles, password);
                            return;
                        }
                        adoptExistingBootstrapAdministrator(jdbc, encoder, password);
                    });
        };
    }

    private static void createBootstrapAdministrator(
            JdbcClient jdbc,
            PasswordEncoder encoder,
            BootstrapRolePort administratorRoles,
            String password) {
        var administrator =
                jdbc.sql(
                                "INSERT INTO platform_user(id,username,display_name,password_hash) VALUES(:id,'admin','管理员',:hash) RETURNING id,primary_credential_revision")
                        .param("id", UUID.randomUUID())
                        .param("hash", encoder.encode(password))
                        .query(
                                (rs, row) ->
                                        new BootstrapAdministrator(
                                                rs.getObject("id", UUID.class),
                                                null,
                                                rs.getObject(
                                                        "primary_credential_revision", UUID.class),
                                                true))
                        .single();
        insertBootstrapRequirement(jdbc, administrator.id(), administrator.revision(), false);
        administratorRoles.assignAdministratorRole(administrator.id());
    }

    /**
     * 旧安装尚无持久化标记时，仅在现有管理员哈希仍能验证配置的初始密码时接管；若保留着明确的改密或重置审计，则优先记录完成，避免再次强制替换管理员已经改过的密码。
     *
     * <p>Older installations predate the durable marker. Adopt only the existing admin account when
     * its current hash still proves the configured bootstrap password. A retained positive
     * password-change/reset audit takes precedence and records completion instead of forcing a
     * password the administrator has previously replaced.
     */
    private static void adoptExistingBootstrapAdministrator(
            JdbcClient jdbc, PasswordEncoder encoder, String password) {
        var administrator =
                jdbc.sql(
                                "SELECT id,password_hash,primary_credential_revision,enabled FROM platform_user WHERE username='admin' AND deleted_at IS NULL FOR UPDATE")
                        .query(
                                (rs, row) ->
                                        new BootstrapAdministrator(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("password_hash"),
                                                rs.getObject(
                                                        "primary_credential_revision", UUID.class),
                                                rs.getBoolean("enabled")))
                        .optional()
                        .orElse(null);
        if (administrator == null) return;

        var existingMarker =
                jdbc.sql(
                                "SELECT primary_credential_revision,completed_at FROM platform_bootstrap_password_change_requirement WHERE user_id=:id FOR UPDATE")
                        .param("id", administrator.id())
                        .query(
                                (rs, row) ->
                                        new BootstrapRequirement(
                                                rs.getObject(
                                                        "primary_credential_revision", UUID.class),
                                                rs.getTimestamp("completed_at") == null))
                        .optional()
                        .orElse(null);
        if (existingMarker != null) {
            if (!existingMarker.active()) return;
            if (hasPasswordChangeAudit(jdbc, administrator.id())
                    || hasTemporaryReset(jdbc, administrator.id())) {
                completeBootstrapRequirement(jdbc, administrator.id());
                return;
            }
            if (!existingMarker.revision().equals(administrator.revision()))
                bindBootstrapRequirement(jdbc, administrator.id(), administrator.revision());
            revokeBootstrapSessionsAndChallenges(jdbc, administrator.id());
            return;
        }
        // 禁用用户也需持久化标记，避免重新启用管理员后，未更换的初始密码被当作普通认证登录。
        // Disabled users still need the durable marker so re-enabling an admin cannot turn an
        // unmodified bootstrap password into a normal authenticated login.
        if (!encoder.matches(password, administrator.passwordHash())) return;

        boolean passwordWasChanged = hasPasswordChangeAudit(jdbc, administrator.id());
        boolean hasTemporaryReset = hasTemporaryReset(jdbc, administrator.id());
        if (passwordWasChanged || hasTemporaryReset) {
            insertBootstrapRequirement(jdbc, administrator.id(), administrator.revision(), true);
            return;
        }

        UUID revision =
                jdbc.sql(
                                "UPDATE platform_user SET primary_credential_revision=gen_random_uuid() WHERE id=:id RETURNING primary_credential_revision")
                        .param("id", administrator.id())
                        .query(UUID.class)
                        .single();
        insertBootstrapRequirement(jdbc, administrator.id(), revision, false);
        revokeBootstrapSessionsAndChallenges(jdbc, administrator.id());
    }

    private static void revokeBootstrapSessionsAndChallenges(JdbcClient jdbc, UUID userId) {
        jdbc.sql("DELETE FROM platform_login_challenge WHERE user_id=:id")
                .param("id", userId)
                .update();
        jdbc.sql("DELETE FROM spring_session WHERE principal_name='admin'").update();
    }

    private static boolean hasPasswordChangeAudit(JdbcClient jdbc, UUID userId) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_audit WHERE target_id=:id AND action IN (:actions))")
                .param("id", userId)
                .param(
                        "actions",
                        List.of(
                                "account.passwordChanged",
                                "account.passwordRecovered",
                                "account.smsPasswordRecovered",
                                "account.forcedPasswordChanged",
                                "account.passwordReset",
                                "user.passwordReset"))
                .query(Boolean.class)
                .single();
    }

    private static boolean hasTemporaryReset(JdbcClient jdbc, UUID userId) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_password_change_requirement WHERE user_id=:id)")
                .param("id", userId)
                .query(Boolean.class)
                .single();
    }

    static void insertBootstrapRequirement(
            JdbcClient jdbc, UUID userId, UUID revision, boolean completed) {
        jdbc.sql(
                        "INSERT INTO platform_bootstrap_password_change_requirement(user_id,primary_credential_revision,completed_at) VALUES(:id,:revision,CASE WHEN :completed THEN CURRENT_TIMESTAMP ELSE NULL END) ON CONFLICT(user_id) DO NOTHING")
                .param("id", userId)
                .param("revision", revision)
                .param("completed", completed)
                .update();
    }

    static void completeBootstrapRequirement(JdbcClient jdbc, UUID userId) {
        jdbc.sql(
                        "UPDATE platform_bootstrap_password_change_requirement SET completed_at=GREATEST(COALESCE(completed_at,created_at),CURRENT_TIMESTAMP) WHERE user_id=:id")
                .param("id", userId)
                .update();
    }

    static void bindBootstrapRequirement(JdbcClient jdbc, UUID userId, UUID revision) {
        jdbc.sql(
                        "UPDATE platform_bootstrap_password_change_requirement SET primary_credential_revision=:revision WHERE user_id=:id AND completed_at IS NULL")
                .param("id", userId)
                .param("revision", revision)
                .update();
    }

    private record BootstrapAdministrator(
            UUID id, String passwordHash, UUID revision, boolean enabled) {}

    private record BootstrapRequirement(UUID revision, boolean active) {}
}
