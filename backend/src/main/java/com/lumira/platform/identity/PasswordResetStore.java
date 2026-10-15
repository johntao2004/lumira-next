package com.lumira.platform.identity;

import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.identity.api.AccountSecurityPort;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal only: callers provide generic public responses, rate limits and same-transaction
 * mail/audit.
 */
@Repository
public class PasswordResetStore {
    public record Preflight(UUID userId, String username, String passwordHash) {}

    private final JdbcClient jdbc;
    private final SecurityPolicyPort security;
    private final AuthenticationPolicyWriteLock policyLock;
    private final AccountSecurityPort accountSecurity;

    @org.springframework.beans.factory.annotation.Autowired
    public PasswordResetStore(
            JdbcClient jdbc,
            SecurityPolicyPort security,
            AuthenticationPolicyWriteLock policyLock,
            AccountSecurityPort accountSecurity) {
        this.jdbc = jdbc;
        this.security = security;
        this.policyLock = policyLock;
        this.accountSecurity = accountSecurity;
    }

    public PasswordResetStore(JdbcClient jdbc, SecurityPolicyPort security) {
        this(jdbc, security, null, null);
    }

    public PasswordResetStore(JdbcClient jdbc) {
        this(jdbc, null, null, null);
    }

    private SecurityPolicyPort.Policy policy() {
        return security == null ? SecurityPolicyPort.Policy.defaults() : security.currentPolicy();
    }

    private record Account(String username, String passwordHash, boolean enabled) {}

    private record Email(String email, long version) {}

    private Optional<Account> account(UUID id) {
        return jdbc.sql(
                        "SELECT username,password_hash,enabled FROM platform_user WHERE id=:id FOR UPDATE")
                .param("id", id)
                .query(Account.class)
                .optional();
    }

    private Optional<Email> email(UUID id) {
        return jdbc.sql(
                        "SELECT email,version FROM platform_account_email WHERE user_id=:id AND email IS NOT NULL FOR UPDATE")
                .param("id", id)
                .query(Email.class)
                .optional();
    }

    /** Read-only eligibility snapshot; callers must recheck it under the final account lock. */
    public Optional<Preflight> preflight(String username, String token) {
        if (username == null || username.isBlank() || username.length() > 100 || token == null)
            return Optional.empty();
        var currentPolicy = policy();
        return jdbc.sql(
                        """
                        SELECT u.id AS user_id,u.username,u.password_hash
                          FROM platform_user u
                          JOIN platform_account_email e ON e.user_id=u.id
                               AND e.email IS NOT NULL AND e.verified_at IS NOT NULL
                          JOIN platform_password_reset r ON r.user_id=u.id
                         WHERE u.username=:username AND u.enabled AND u.deleted_at IS NULL
                           AND r.token_hash=:token AND r.expires_at>CURRENT_TIMESTAMP
                           AND r.failures<:failureLimit
                           AND r.password_snapshot=u.password_hash AND r.email_version=e.version
                        """)
                .param("username", username)
                .param("token", VerificationToken.digest(token))
                .param("failureLimit", currentPolicy.verificationFailureLimit())
                .query(
                        (rs, row) ->
                                new Preflight(
                                        rs.getObject("user_id", UUID.class),
                                        rs.getString("username"),
                                        rs.getString("password_hash")))
                .optional();
    }

    /** Commit a failed-token count without performing any password hashing. */
    @Transactional
    public boolean recordFailure(UUID id, String token) {
        if (id == null) return false;
        if (policyLock != null) policyLock.lockForAuthentication();
        var account = account(id);
        if (account.isEmpty() || !account.get().enabled()) return false;
        return jdbc.sql(
                                """
                        UPDATE platform_password_reset
                           SET failures=LEAST(:failureLimit,failures+1)
                         WHERE user_id=:id AND token_hash IS NOT NULL
                           AND (:token IS NULL OR token_hash<>:token)
                        """)
                        .param("id", id)
                        .param("token", token == null ? null : VerificationToken.digest(token))
                        .param("failureLimit", policy().verificationFailureLimit())
                        .update()
                == 1;
    }

    /** Only a verified address is returned, and only when a new challenge has been stored. */
    @Transactional
    public Optional<String> request(UUID id, VerificationToken.Issued token) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(token);
        if (token.digest() == null
                || token.digest().isEmpty()
                || !token.digest().equals(VerificationToken.digest(token.value())))
            throw new IllegalArgumentException("Invalid verification token");
        var account = account(id);
        if (account.isEmpty() || !account.get().enabled()) return Optional.empty();
        var policy = policy();
        var email = email(id);
        if (email.isEmpty()) return Optional.empty();
        int count =
                jdbc.sql(
                                """
            INSERT INTO platform_password_reset(user_id,token_hash,password_snapshot,email_version,expires_at,requested_at,request_day,request_count)
            VALUES(:id,:token,:password,:version,now()+(:ttl * interval '1 second'),now(),CURRENT_DATE,1)
            ON CONFLICT(user_id) DO UPDATE SET token_hash=EXCLUDED.token_hash,password_snapshot=EXCLUDED.password_snapshot,
              email_version=EXCLUDED.email_version,expires_at=EXCLUDED.expires_at,requested_at=now(),failures=0,
              request_count=CASE WHEN platform_password_reset.request_day=CURRENT_DATE THEN platform_password_reset.request_count+1 ELSE 1 END,
              request_day=CURRENT_DATE
            WHERE platform_password_reset.requested_at<=now()-(:cooldown * interval '1 second')
              AND (platform_password_reset.request_day<>CURRENT_DATE OR platform_password_reset.request_count<:daily)
            """)
                        .param("id", id)
                        .param("token", token.digest())
                        .param("password", account.get().passwordHash())
                        .param("version", email.get().version())
                        .param("ttl", policy.verificationCodeExpireSeconds())
                        .param("cooldown", policy.verificationCodeCooldownSeconds())
                        .param("daily", policy.verificationDailyLimit())
                        .update();
        return count == 1 ? Optional.of(email.get().email()) : Optional.empty();
    }

    /** Final locked compare-and-set for a hash prepared outside the database transaction. */
    @Transactional
    public boolean consume(
            UUID id,
            String token,
            String expectedPasswordHash,
            String replacementHash,
            String newPassword,
            SecurityPolicyPort.Policy expectedPolicy) {
        Objects.requireNonNull(id);
        if (expectedPasswordHash == null || replacementHash == null || replacementHash.isBlank())
            throw new IllegalArgumentException("Password hashes required");
        if (policyLock != null) policyLock.lockForAuthentication();
        var account = account(id);
        if (account.isEmpty() || !account.get().enabled()) return false;
        var email = email(id);
        if (email.isEmpty()) return false;
        var currentPolicy = policy();
        if (!Objects.equals(expectedPolicy, currentPolicy)
                || !PasswordPolicy.valid(newPassword, currentPolicy))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "passwordPolicyChanged");
        boolean valid =
                jdbc.sql(
                                """
                        SELECT token_hash=:token AND expires_at>CURRENT_TIMESTAMP AND failures<:failure
                           AND password_snapshot=:password AND email_version=:version
                           AND :expected=:password
                          FROM platform_password_reset WHERE user_id=:id FOR UPDATE
                        """)
                        .param("id", id)
                        .param("token", VerificationToken.digest(token))
                        .param("password", account.get().passwordHash())
                        .param("version", email.get().version())
                        .param("failure", currentPolicy.verificationFailureLimit())
                        .param("expected", expectedPasswordHash)
                        .query((rs, row) -> rs.getBoolean(1))
                        .optional()
                        .orElse(false);
        if (!valid) {
            jdbc.sql(
                            "UPDATE platform_password_reset SET failures=LEAST(:failure,failures+1) WHERE user_id=:id AND token_hash IS NOT NULL")
                    .param("id", id)
                    .param("failure", currentPolicy.verificationFailureLimit())
                    .update();
            return false;
        }
        jdbc.sql("UPDATE platform_user SET password_hash=:password WHERE id=:id")
                .param("id", id)
                .param("password", replacementHash)
                .update();
        if (accountSecurity != null) accountSecurity.bumpPrimaryCredentialRevision(id);
        jdbc.sql("DELETE FROM platform_password_change_requirement WHERE user_id=:id")
                .param("id", id)
                .update();
        IdentityConfiguration.completeBootstrapRequirement(jdbc, id);
        jdbc.sql("DELETE FROM spring_session WHERE principal_name=:name")
                .param("name", account.get().username())
                .update();
        jdbc.sql(
                        "UPDATE platform_password_reset SET token_hash=NULL,password_snapshot=NULL,email_version=NULL,expires_at=NULL,failures=0 WHERE user_id=:id")
                .param("id", id)
                .update();
        return true;
    }
}
