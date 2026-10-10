package com.lumira.platform.identity;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.identity.api.AccountSecurityPort;
import com.lumira.platform.identity.api.AuthorizationContextBoundaryPort;
import com.lumira.platform.identity.api.PasswordContract;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Password change keeps expensive verification and Argon2 work outside database locks. */
@Service
public class PasswordService {
    private record Account(UUID id, String passwordHash) {}

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuditPort audit;
    private final SecurityPolicyPort security;
    private final AuthenticationPolicyWriteLock policyLock;
    private final AccountSecurityPort accountSecurity;
    private final TransactionTemplate writeTransaction;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuthorizationContextBoundaryPort authorizationBoundary;

    @Autowired
    public PasswordService(
            JdbcClient jdbc,
            PasswordEncoder encoder,
            AuditPort audit,
            SecurityPolicyPort security,
            AuthenticationPolicyWriteLock policyLock,
            AccountSecurityPort accountSecurity,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.security = security;
        this.policyLock = policyLock;
        this.accountSecurity = accountSecurity;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setTimeout(10);
    }

    /** Compatibility constructors are limited to validation-only unit tests. */
    public PasswordService(
            JdbcClient jdbc,
            PasswordEncoder encoder,
            AuditPort audit,
            SecurityPolicyPort security) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.security = security;
        this.policyLock = null;
        this.accountSecurity = null;
        this.writeTransaction = null;
    }

    public PasswordService(JdbcClient jdbc, PasswordEncoder encoder, AuditPort audit) {
        this(jdbc, encoder, audit, null);
    }

    public void change(String username, String currentPassword, String newPassword) {
        SecurityPolicyPort.Policy policySnapshot = currentPolicy();
        if (currentPassword == null
                || currentPassword.isBlank()
                || currentPassword.length() > 1024
                || !PasswordPolicy.valid(newPassword, policySnapshot)) {
            throw error(HttpStatus.BAD_REQUEST, "passwordPolicy");
        }

        Account preflight =
                jdbc.sql(
                                """
                        SELECT id,password_hash FROM platform_user
                         WHERE username=:name AND enabled AND deleted_at IS NULL
                        """)
                        .param("name", username)
                        .query(Account.class)
                        .optional()
                        .orElseThrow(
                                () -> error(HttpStatus.UNAUTHORIZED, "authenticationRequired"));

        if (!PasswordHashCapacity.tryAcquire())
            throw error(HttpStatus.TOO_MANY_REQUESTS, "passwordHashCapacity");
        String replacementHash;
        try {
            if (!PasswordContract.compatibleWithStoredHash(
                            currentPassword, preflight.passwordHash())
                    || !encoder.matches(currentPassword, preflight.passwordHash())) {
                throw error(HttpStatus.BAD_REQUEST, "wrongCurrentPassword");
            }
            if (PasswordContract.compatibleWithStoredHash(newPassword, preflight.passwordHash())
                    && encoder.matches(newPassword, preflight.passwordHash())) {
                throw error(HttpStatus.BAD_REQUEST, "passwordMustDiffer");
            }
            replacementHash = encoder.encode(newPassword);
        } finally {
            PasswordHashCapacity.release();
        }

        if (writeTransaction == null)
            throw new IllegalStateException("Password changes require a transaction manager");
        writeTransaction.executeWithoutResult(
                status -> {
                    if (policyLock != null) policyLock.lockForAuthentication();
                    if (authorizationBoundary != null)
                        authorizationBoundary.lockAndValidateCurrentRequest();
                    Account current =
                            jdbc.sql(
                                            """
                            SELECT id,password_hash FROM platform_user
                             WHERE username=:name AND enabled AND deleted_at IS NULL FOR UPDATE
                            """)
                                    .param("name", username)
                                    .query(Account.class)
                                    .optional()
                                    .orElseThrow(
                                            () ->
                                                    error(
                                                            HttpStatus.UNAUTHORIZED,
                                                            "authenticationRequired"));
                    if (!current.id().equals(preflight.id())
                            || !Objects.equals(current.passwordHash(), preflight.passwordHash())) {
                        throw error(HttpStatus.CONFLICT, "passwordChangeExpired");
                    }
                    SecurityPolicyPort.Policy latestPolicy = currentPolicy();
                    if (!Objects.equals(policySnapshot, latestPolicy)
                            || !PasswordPolicy.valid(newPassword, latestPolicy)) {
                        throw error(HttpStatus.CONFLICT, "passwordPolicyChanged");
                    }
                    int changed =
                            jdbc.sql(
                                            """
                            UPDATE platform_user SET password_hash=:hash
                             WHERE id=:id AND password_hash=:expected AND enabled AND deleted_at IS NULL
                            """)
                                    .param("hash", replacementHash)
                                    .param("id", current.id())
                                    .param("expected", preflight.passwordHash())
                                    .update();
                    if (changed != 1) throw error(HttpStatus.CONFLICT, "passwordChangeExpired");
                    if (accountSecurity != null)
                        accountSecurity.bumpPrimaryCredentialRevision(current.id());
                    jdbc.sql("DELETE FROM spring_session WHERE principal_name=:name")
                            .param("name", username)
                            .update();
                    audit.append(username, "account.passwordChanged", current.id());
                });
    }

    private SecurityPolicyPort.Policy currentPolicy() {
        return security == null ? SecurityPolicyPort.Policy.defaults() : security.currentPolicy();
    }

    private static ResponseStatusException error(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }
}
