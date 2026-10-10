package com.lumira.platform.identity;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.AuthorizationContextBoundaryPort;
import com.lumira.platform.common.SmsSender;
import com.lumira.platform.identity.api.AccountSecurityPort;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountPhoneService {
    public record Request(
            long version,
            String phone,
            @com.fasterxml.jackson.annotation.JsonProperty(
                            access =
                                    com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
                    String currentPassword,
            String locale) {
        @Override
        public String toString() {
            return "PhoneVerificationRequest[redacted]";
        }
    }

    private final ProfileRepository profiles;
    private final AccountPhoneStore phones;
    private final SmsSender sms;
    private final AuditPort audit;
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final PhoneVerificationCode codes;
    private final AuthenticationPolicyWriteLock policyLock;
    private final AccountSecurityPort accountSecurity;
    private final AuthorizationContextBoundaryPort authorizationBoundary;
    private final TransactionTemplate writeTransaction;

    @Autowired
    public AccountPhoneService(
            ProfileRepository profiles,
            AccountPhoneStore phones,
            SmsSender sms,
            AuditPort audit,
            JdbcClient jdbc,
            PasswordEncoder passwords,
            PhoneVerificationCode codes,
            AuthenticationPolicyWriteLock policyLock,
            AccountSecurityPort accountSecurity,
            AuthorizationContextBoundaryPort authorizationBoundary,
            PlatformTransactionManager transactionManager) {
        this.profiles = profiles;
        this.phones = phones;
        this.sms = sms;
        this.audit = audit;
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.codes = codes;
        this.policyLock = policyLock;
        this.accountSecurity = accountSecurity;
        this.authorizationBoundary = authorizationBoundary;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setTimeout(10);
    }

    public AccountPhoneStore.View read(String actor) {
        return phones.read(profiles.find(actor, false).id());
    }

    /** Password hashing stays outside the short transaction and session-context lock. */
    public AccountPhoneStore.View request(String actor, Request input) {
        if (input == null || input.phone() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidPhone");
        String phone = normalize(input.phone());
        var preflightAccount = profiles.find(actor, false);
        String preflightHash = AccountCredentialPreflight.passwordHash(jdbc, preflightAccount.id());
        AccountCredentialPreflight.requireCurrentPassword(
                passwords, input.currentPassword(), preflightHash);
        if (!sms.ready())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "smsUnavailable");

        VerificationToken.Issued token = codes.issue(preflightAccount.id());
        return Objects.requireNonNull(
                writeTransaction.execute(
                        status -> {
                            AccountCredentialPreflight.lockAuthenticationWriteBoundary(
                                    policyLock, authorizationBoundary);
                            var account = profiles.find(actor, true);
                            if (!account.id().equals(preflightAccount.id())
                                    || !Objects.equals(
                                            AccountCredentialPreflight.passwordHash(
                                                    jdbc, account.id()),
                                            preflightHash)) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "wrongCurrentPassword");
                            }

                            var pending =
                                    phones.request(account.id(), input.version(), phone, token);
                            int lifetime =
                                    (int)
                                            Math.max(
                                                    1,
                                                    java.time.Duration.between(
                                                                    java.time.OffsetDateTime.now(),
                                                                    pending.expiresAt())
                                                            .getSeconds());
                            if (!sms.enqueueVerification(
                                    java.util.UUID.randomUUID(), phone, token.value(), lifetime)) {
                                throw new IllegalStateException("SMS task collision");
                            }
                            audit.append(actor, "account.phoneVerificationRequested", account.id());
                            return phones.read(account.id());
                        }),
                "phone request transaction returned no result");
    }

    public boolean confirm(String actor, String token) {
        return Boolean.TRUE.equals(
                writeTransaction.execute(
                        status -> {
                            AccountCredentialPreflight.lockAuthenticationWriteBoundary(
                                    policyLock, authorizationBoundary);
                            var account = profiles.find(actor, true);
                            boolean confirmed = phones.confirm(account.id(), token);
                            if (confirmed && accountSecurity != null)
                                accountSecurity.bumpPrimaryCredentialRevision(account.id());
                            audit.append(
                                    actor,
                                    confirmed
                                            ? "account.phoneVerified"
                                            : "account.phoneVerificationFailed",
                                    account.id());
                            return confirmed;
                        }));
    }

    static String normalize(String value) {
        String phone =
                value.strip().replace(" ", "").replace("-", "").replace("(", "").replace(")", "");
        if (!phone.matches("\\+[1-9]\\d{6,14}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidPhone");
        return phone;
    }
}
