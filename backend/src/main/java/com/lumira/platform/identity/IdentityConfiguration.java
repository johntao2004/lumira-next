package com.lumira.platform.identity;

import java.util.HashMap;
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
     * Argon2id is the write path. The explicit id makes the parameters part of the stored contract,
     * while the raw BCrypt fallback keeps existing rows readable during a rolling migration.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        var argon2 = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
        var bcrypt = new BCryptPasswordEncoder(12);
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put(ARGON2_ID, argon2);
        encoders.put("bcrypt", bcrypt);
        var delegating = new DelegatingPasswordEncoder(ARGON2_ID, encoders);
        // Existing Lumira rows predate DelegatingPasswordEncoder and contain raw BCrypt.
        delegating.setDefaultPasswordEncoderForMatches(bcrypt);
        return delegating;
    }

    /** Upgrade a legacy hash only when the presented password has already passed. */
    @Bean
    UserDetailsPasswordService userDetailsPasswordService(JdbcClient jdbc) {
        return (user, newPassword) -> {
            jdbc.sql(
                            "UPDATE platform_user SET password_hash=:password WHERE username=:username AND password_hash=:oldHash AND enabled=true")
                    .param("password", newPassword)
                    .param("username", user.getUsername())
                    .param("oldHash", user.getPassword())
                    .update();
            if (user instanceof PasswordProofUserDetails proofUser) {
                return new PasswordProofUserDetails(
                        user.getUsername(),
                        newPassword,
                        user.isEnabled(),
                        proofUser.primaryCredentialRevision(),
                        user.getAuthorities());
            }
            return User.withUserDetails(user).password(newPassword).build();
        };
    }

    /** Make Spring Security's successful-login rehash hook explicit and testable. */
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
                    jdbc.sql("SELECT authority FROM platform_effective_authority WHERE user_id=:id")
                            .param("id", account.id())
                            .query(String.class)
                            .list();
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
            @Value("${lumira.bootstrap.password}") String password) {
        return args -> {
            if (password.isBlank()) return;
            if (password.length() < 16)
                throw new IllegalArgumentException(
                        "Bootstrap password must contain at least 16 characters");
            tx.executeWithoutResult(
                    status -> {
                        // Serializes initialization across concurrently starting instances.
                        jdbc.sql("SELECT pg_advisory_xact_lock(481025)")
                                .query(
                                        rs -> {
                                            rs.next();
                                            return 0;
                                        });
                        if (jdbc.sql("SELECT count(*) FROM platform_user")
                                        .query(Long.class)
                                        .single()
                                != 0) return;
                        var id = UUID.randomUUID();
                        jdbc.sql(
                                        "INSERT INTO platform_user(id,username,display_name,password_hash) VALUES(:id,'admin','管理员',:hash)")
                                .param("id", id)
                                .param("hash", encoder.encode(password))
                                .update();
                        for (String authority :
                                new String[] {
                                    "platform:feedback:read",
                                    "platform:feedback:create",
                                    "platform:feedback:manage",
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
                                    "platform:sensitive-words:read",
                                    "platform:sensitive-words:write",
                                    "platform:sensitive-words:import",
                                    "platform:localization:read",
                                    "platform:localization:write",
                                    "platform:localization:import",
                                    "platform:files:read",
                                    "platform:files:write",
                                    "platform:notifications:read",
                                    "platform:notifications:write"
                                })
                            jdbc.sql(
                                            "INSERT INTO platform_authority(user_id,authority) VALUES(:id,:authority)")
                                    .param("id", id)
                                    .param("authority", authority)
                                    .update();
                    });
        };
    }
}
