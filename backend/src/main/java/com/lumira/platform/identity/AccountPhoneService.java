package com.lumira.platform.identity;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.SmsSender;
import com.lumira.platform.identity.api.PasswordContract;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountPhoneService {
    public record Request(long version, String phone,
            @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String currentPassword,
            String locale) { @Override public String toString() { return "PhoneVerificationRequest[redacted]"; } }
    private final ProfileRepository profiles; private final AccountPhoneStore phones; private final SmsSender sms;
    private final AuditPort audit; private final JdbcClient jdbc; private final PasswordEncoder passwords;
    public AccountPhoneService(ProfileRepository profiles, AccountPhoneStore phones, SmsSender sms,
                                AuditPort audit, JdbcClient jdbc, PasswordEncoder passwords) {
        this.profiles=profiles; this.phones=phones; this.sms=sms; this.audit=audit; this.jdbc=jdbc; this.passwords=passwords;
    }
    @Transactional(readOnly = true) public AccountPhoneStore.View read(String actor) { return phones.read(profiles.find(actor, false).id()); }
    @Transactional
    public AccountPhoneStore.View request(String actor, Request input) {
        if (input == null || input.phone() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidPhone");
        String phone=normalize(input.phone());
        var account=profiles.find(actor, true);
        String hash=jdbc.sql("SELECT password_hash FROM platform_user WHERE id=:id").param("id", account.id()).query(String.class).single();
        if (!PasswordContract.compatibleWithStoredHash(input.currentPassword(), hash) || !passwords.matches(input.currentPassword(), hash))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "wrongCurrentPassword");
        VerificationToken.Issued token=VerificationToken.issue();
        String text=sms.verificationMessage(token.value());
        phones.request(account.id(), input.version(), phone, token);
        if (!sms.enqueue(java.util.UUID.randomUUID(), phone, text, 600)) throw new IllegalStateException("SMS task collision");
        audit.append(actor, "account.phoneVerificationRequested", account.id()); return phones.read(account.id());
    }
    @Transactional
    public boolean confirm(String actor, String token) {
        var account=profiles.find(actor, true); boolean confirmed=phones.confirm(account.id(), token);
        audit.append(actor, confirmed ? "account.phoneVerified" : "account.phoneVerificationFailed", account.id()); return confirmed;
    }
    static String normalize(String value) {
        String phone=value.strip().replace(" ", "").replace("-", "").replace("(", "").replace(")", "");
        if (!phone.matches("\\+[1-9]\\d{6,14}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidPhone");
        return phone;
    }
}
