package com.lumira.platform.identity;

import com.lumira.platform.identity.api.SecurityPolicyPort;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Internal only: callers provide generic public responses, rate limits and same-transaction mail/audit. */
@Repository
public class PasswordResetStore {
    private final JdbcClient jdbc;
    private final SecurityPolicyPort security;
    @org.springframework.beans.factory.annotation.Autowired
    public PasswordResetStore(JdbcClient jdbc,SecurityPolicyPort security){this.jdbc=jdbc;this.security=security;}
    public PasswordResetStore(JdbcClient jdbc){this(jdbc,null);}
    private SecurityPolicyPort.Policy policy(){return security==null?SecurityPolicyPort.Policy.defaults():security.currentPolicy();}
    private record Account(String username,String passwordHash,boolean enabled){}
    private record Email(String email,long version){}
    private Optional<Account> account(UUID id){return jdbc.sql("SELECT username,password_hash,enabled FROM platform_user WHERE id=:id FOR UPDATE")
        .param("id",id).query(Account.class).optional();}
    private Optional<Email> email(UUID id){return jdbc.sql("SELECT email,version FROM platform_account_email WHERE user_id=:id AND email IS NOT NULL FOR UPDATE")
        .param("id",id).query(Email.class).optional();}

    /** Only a verified address is returned, and only when a new challenge has been stored. */
    @Transactional
    public Optional<String> request(UUID id,VerificationToken.Issued token){
        Objects.requireNonNull(id);Objects.requireNonNull(token);
        if(token.digest()==null||token.digest().isEmpty()||!token.digest().equals(VerificationToken.digest(token.value())))throw new IllegalArgumentException("Invalid verification token");
        var account=account(id);if(account.isEmpty()||!account.get().enabled())return Optional.empty(); var policy=policy();
        var email=email(id);if(email.isEmpty())return Optional.empty();
        int count=jdbc.sql("""
            INSERT INTO platform_password_reset(user_id,token_hash,password_snapshot,email_version,expires_at,requested_at,request_day,request_count)
            VALUES(:id,:token,:password,:version,now()+(:ttl * interval '1 second'),now(),CURRENT_DATE,1)
            ON CONFLICT(user_id) DO UPDATE SET token_hash=EXCLUDED.token_hash,password_snapshot=EXCLUDED.password_snapshot,
              email_version=EXCLUDED.email_version,expires_at=EXCLUDED.expires_at,requested_at=now(),failures=0,
              request_count=CASE WHEN platform_password_reset.request_day=CURRENT_DATE THEN platform_password_reset.request_count+1 ELSE 1 END,
              request_day=CURRENT_DATE
            WHERE platform_password_reset.requested_at<=now()-(:cooldown * interval '1 second')
              AND (platform_password_reset.request_day<>CURRENT_DATE OR platform_password_reset.request_count<:daily)
            """).param("id",id).param("token",token.digest()).param("password",account.get().passwordHash()).param("version",email.get().version())
            .param("ttl",policy.verificationCodeExpireSeconds()).param("cooldown",policy.verificationCodeCooldownSeconds())
            .param("daily",policy.verificationDailyLimit()).update();
        return count==1?Optional.of(email.get().email()):Optional.empty();
    }

    /** Accepts an encoded password from a trusted service; false must commit to retain failed attempts. */
    @Transactional
    public boolean consume(UUID id,String token,String encodedPassword){
        Objects.requireNonNull(id);
        if(encodedPassword==null||encodedPassword.isBlank())throw new IllegalArgumentException("Encoded password required");
        return consume(id,token,oldHash->encodedPassword);
    }
    /** Password policy callback runs only after proof validation, under the account lock. */
    @Transactional
    public boolean consume(UUID id,String token,java.util.function.Function<String,String> replacement){
        var account=account(id);if(account.isEmpty()||!account.get().enabled())return false;
        var email=email(id);if(email.isEmpty())return false; var policy=policy();
        boolean valid=jdbc.sql("""
            SELECT token_hash=:token AND expires_at>now() AND failures<:failure AND password_snapshot=:password AND email_version=:version
            FROM platform_password_reset WHERE user_id=:id FOR UPDATE
            """).param("id",id).param("token",VerificationToken.digest(token)).param("password",account.get().passwordHash()).param("version",email.get().version()).param("failure",policy.verificationFailureLimit())
            .query((rs,n)->rs.getBoolean(1)).optional().orElse(false);
        if(!valid){jdbc.sql("UPDATE platform_password_reset SET failures=least(:failure,failures+1) WHERE user_id=:id AND token_hash IS NOT NULL").param("id",id).param("failure",policy.verificationFailureLimit()).update();return false;}
        String encodedPassword=Objects.requireNonNull(replacement.apply(account.get().passwordHash()));
        if(encodedPassword.isBlank())throw new IllegalArgumentException("Encoded password required");
        jdbc.sql("UPDATE platform_user SET password_hash=:password WHERE id=:id").param("id",id).param("password",encodedPassword).update();
        jdbc.sql("DELETE FROM spring_session WHERE principal_name=:name").param("name",account.get().username()).update();
        jdbc.sql("UPDATE platform_password_reset SET token_hash=NULL,password_snapshot=NULL,email_version=NULL,expires_at=NULL,failures=0 WHERE user_id=:id").param("id",id).update();
        return true;
    }
}
