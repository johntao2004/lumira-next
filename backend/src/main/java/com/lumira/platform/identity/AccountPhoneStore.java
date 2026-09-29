package com.lumira.platform.identity;

import com.lumira.platform.identity.api.SecurityPolicyPort;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Owner-scoped phone binding state. Codes are represented only by a digest. */
@Repository
public class AccountPhoneStore {
    public record View(String phone, OffsetDateTime verifiedAt, long version, String pendingPhone,
                       OffsetDateTime expiresAt, OffsetDateTime requestedAt) {}
    private final JdbcClient jdbc; private final SecurityPolicyPort security;
    @org.springframework.beans.factory.annotation.Autowired
    public AccountPhoneStore(JdbcClient jdbc,SecurityPolicyPort security) { this.jdbc = jdbc; this.security=security; }
    public AccountPhoneStore(JdbcClient jdbc) { this(jdbc,null); }
    private SecurityPolicyPort.Policy policy(){return security==null?SecurityPolicyPort.Policy.defaults():security.currentPolicy();}
    public View read(UUID owner) {
        return jdbc.sql("SELECT phone,verified_at,version,pending_phone,expires_at,requested_at FROM platform_account_phone WHERE user_id=:id")
            .param("id", owner).query(View.class).optional().orElse(new View(null, null, 0, null, null, null));
    }
    private void lock(UUID owner) {
        jdbc.sql("INSERT INTO platform_account_phone(user_id) VALUES(:id) ON CONFLICT DO NOTHING").param("id", owner).update();
        jdbc.sql("SELECT user_id FROM platform_account_phone WHERE user_id=:id FOR UPDATE").param("id", owner).query(UUID.class).single();
    }
    @Transactional
    public View request(UUID owner, long expectedVersion, String phone, VerificationToken.Issued token) {
        Objects.requireNonNull(owner); Objects.requireNonNull(token);
        if (!phone.matches("\\+[1-9]\\d{6,14}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidPhone");
        if (token.digest() == null || token.digest().isEmpty() || !token.digest().equals(VerificationToken.digest(token.value())))
            throw new IllegalArgumentException("Invalid verification token");
        lock(owner); var current=read(owner); var policy=policy();
        if (current.version() != expectedVersion) throw new ResponseStatusException(HttpStatus.CONFLICT, "staleRecord");
        if (phone.equals(current.phone())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "phoneAlreadyVerified");
        int updated=jdbc.sql("""
            UPDATE platform_account_phone SET pending_phone=:phone,token_hash=:hash,expires_at=now()+(:ttl * interval '1 second'),
              requested_at=now(),request_count=CASE WHEN request_day=CURRENT_DATE THEN request_count+1 ELSE 1 END,
              request_day=CURRENT_DATE,failures=0,version=version+1
            WHERE user_id=:id AND (requested_at IS NULL OR requested_at<=now()-(:cooldown * interval '1 second'))
              AND (request_day IS NULL OR request_day<>CURRENT_DATE OR request_count<:daily)
            """).param("id", owner).param("phone", phone).param("hash", token.digest())
            .param("ttl",policy.verificationCodeExpireSeconds()).param("cooldown",policy.verificationCodeCooldownSeconds())
            .param("daily",policy.verificationDailyLimit()).update();
        if (updated != 1) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "phoneVerificationLimited");
        return read(owner);
    }
    /** Failed attempts are committed independently of the HTTP error response. */
    @Transactional
    public boolean confirm(UUID owner, String token) {
        lock(owner); String digest=VerificationToken.digest(token); var policy=policy();
        boolean valid=jdbc.sql("SELECT token_hash=:hash AND expires_at>now() AND failures<:failure FROM platform_account_phone WHERE user_id=:id")
            .param("id", owner).param("hash", digest).param("failure",policy.verificationFailureLimit()).query((rs,n)->rs.getBoolean(1)).single();
        if (!valid) {
            jdbc.sql("UPDATE platform_account_phone SET failures=least(:failure,failures+1) WHERE user_id=:id AND pending_phone IS NOT NULL").param("id", owner).param("failure",policy.verificationFailureLimit()).update();
            return false;
        }
        jdbc.sql("UPDATE platform_account_phone SET phone=pending_phone,verified_at=now(),pending_phone=NULL,token_hash=NULL,expires_at=NULL,failures=0,version=version+1 WHERE user_id=:id")
            .param("id", owner).update(); return true;
    }
}
