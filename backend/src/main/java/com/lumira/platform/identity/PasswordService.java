package com.lumira.platform.identity;

import com.lumira.platform.identity.api.PasswordContract;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasswordService {
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final com.lumira.platform.common.AuditPort audit;
    private final SecurityPolicyPort security;
    @org.springframework.beans.factory.annotation.Autowired
    public PasswordService(JdbcClient jdbc, PasswordEncoder encoder, com.lumira.platform.common.AuditPort audit,
                           SecurityPolicyPort security) {
        this.jdbc = jdbc; this.encoder = encoder; this.audit = audit; this.security = security;
    }
    /** Kept for focused repository tests that exercise this service without a Spring context. */
    public PasswordService(JdbcClient jdbc, PasswordEncoder encoder, com.lumira.platform.common.AuditPort audit) {
        this(jdbc, encoder, audit, null);
    }

    @Transactional
    public void change(String username, String currentPassword, String newPassword) {
        if (currentPassword == null || !PasswordPolicy.valid(newPassword,
                security == null ? SecurityPolicyPort.Policy.defaults() : security.currentPolicy())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "passwordPolicy");
        }
        String hash = jdbc.sql("SELECT password_hash FROM platform_user WHERE username=:name AND enabled=true FOR UPDATE")
            .param("name", username).query(String.class).optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!PasswordContract.compatibleWithStoredHash(currentPassword, hash)
                || !encoder.matches(currentPassword, hash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "wrongCurrentPassword");
        }
        if (PasswordContract.compatibleWithStoredHash(newPassword, hash) && encoder.matches(newPassword, hash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "passwordMustDiffer");
        }
        jdbc.sql("UPDATE platform_user SET password_hash=:hash WHERE username=:name")
            .param("hash", encoder.encode(newPassword)).param("name", username).update();
        jdbc.sql("DELETE FROM spring_session WHERE principal_name=:name").param("name", username).update();
        var owner=jdbc.sql("SELECT id FROM platform_user WHERE username=:name").param("name",username).query(java.util.UUID.class).single();
        audit.append(username,"account.passwordChanged",owner);
    }
}
