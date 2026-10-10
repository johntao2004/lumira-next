package com.lumira.platform.identity;

import com.lumira.platform.identity.api.SecurityPolicyPort;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal store. Caller must authorize the account and include mail enqueue/audit in its
 * transaction.
 */
@Repository
public class AccountEmailStore {
    public record View(
            String email,
            OffsetDateTime verifiedAt,
            long version,
            String pendingEmail,
            OffsetDateTime expiresAt,
            OffsetDateTime requestedAt) {}

    private final JdbcClient jdbc;
    private final SecurityPolicyPort security;

    public AccountEmailStore(JdbcClient jdbc) {
        this(jdbc, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AccountEmailStore(JdbcClient jdbc, SecurityPolicyPort security) {
        this.jdbc = jdbc;
        this.security = security;
    }

    private SecurityPolicyPort.Policy policy() {
        return security == null ? SecurityPolicyPort.Policy.defaults() : security.currentPolicy();
    }

    public View read(UUID owner) {
        return jdbc.sql(
                        "SELECT email,verified_at,version,pending_email,expires_at,requested_at FROM platform_account_email WHERE user_id=:id")
                .param("id", owner)
                .query(View.class)
                .optional()
                .orElse(new View(null, null, 0, null, null, null));
    }

    private void lock(UUID owner) {
        jdbc.sql("INSERT INTO platform_account_email(user_id) VALUES(:id) ON CONFLICT DO NOTHING")
                .param("id", owner)
                .update();
        jdbc.sql("SELECT user_id FROM platform_account_email WHERE user_id=:id FOR UPDATE")
                .param("id", owner)
                .query(UUID.class)
                .single();
    }

    @Transactional
    public View request(
            UUID owner, long expectedVersion, String address, VerificationToken.Issued token) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(token);
        if (token.digest().isEmpty()
                || !token.digest().equals(VerificationToken.digest(token.value())))
            throw new IllegalArgumentException("Invalid verification token");
        lock(owner);
        var policy = policy();
        var current = read(owner);
        if (current.version() != expectedVersion)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        if (address.equalsIgnoreCase(current.email()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "emailAlreadyVerified");
        int updated =
                jdbc.sql(
                                """
            UPDATE platform_account_email SET pending_email=:email,token_hash=:hash,expires_at=now()+(:ttl * interval '1 second'),
              requested_at=now(),request_count=CASE WHEN request_day=CURRENT_DATE THEN request_count+1 ELSE 1 END,
              request_day=CURRENT_DATE,failures=0,version=version+1
            WHERE user_id=:id AND (requested_at IS NULL OR requested_at<=now()-(:cooldown * interval '1 second'))
              AND (request_day IS NULL OR request_day<>CURRENT_DATE OR request_count<:daily)
            """)
                        .param("id", owner)
                        .param("email", address)
                        .param("hash", token.digest())
                        .param("ttl", policy.verificationCodeExpireSeconds())
                        .param("cooldown", policy.verificationCodeCooldownSeconds())
                        .param("daily", policy.verificationDailyLimit())
                        .update();
        if (updated != 1)
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "emailVerificationLimited");
        return read(owner);
    }

    /** False is returned, not thrown, so failed-attempt accounting commits. */
    @Transactional
    public boolean confirm(UUID owner, String token) {
        lock(owner);
        String digest = VerificationToken.digest(token);
        var policy = policy();
        boolean valid =
                jdbc.sql(
                                "SELECT token_hash=:hash AND expires_at>now() AND failures<:failure FROM platform_account_email WHERE user_id=:id")
                        .param("id", owner)
                        .param("hash", digest)
                        .param("failure", policy.verificationFailureLimit())
                        .query((rs, n) -> rs.getBoolean(1))
                        .single();
        if (!valid) {
            jdbc.sql(
                            "UPDATE platform_account_email SET failures=least(:failure,failures+1) WHERE user_id=:id AND pending_email IS NOT NULL")
                    .param("id", owner)
                    .param("failure", policy.verificationFailureLimit())
                    .update();
            return false;
        }
        jdbc.sql(
                        "UPDATE platform_account_email SET email=pending_email,verified_at=now(),pending_email=NULL,token_hash=NULL,expires_at=NULL,failures=0,version=version+1 WHERE user_id=:id")
                .param("id", owner)
                .update();
        return true;
    }
}
