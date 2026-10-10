package com.lumira.platform.identity;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.AuthorizationContextBoundaryPort;
import com.lumira.platform.common.MailSender;
import com.lumira.platform.identity.api.AccountSecurityPort;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import java.util.Map;
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
public class AccountEmailService {
    public record Request(
            long version,
            String email,
            @com.fasterxml.jackson.annotation.JsonProperty(
                            access =
                                    com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
                    String currentPassword,
            String locale) {
        @Override
        public String toString() {
            return "EmailVerificationRequest[redacted]";
        }
    }

    private final ProfileRepository profiles;
    private final AccountEmailStore emails;
    private final MailSender mail;
    private final AuditPort audit;
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final SecurityPolicyPort security;
    private final AuthenticationPolicyWriteLock policyLock;
    private final AccountSecurityPort accountSecurity;
    private final AuthorizationContextBoundaryPort authorizationBoundary;
    private final TransactionTemplate writeTransaction;

    @Autowired
    public AccountEmailService(
            ProfileRepository profiles,
            AccountEmailStore emails,
            MailSender mail,
            AuditPort audit,
            JdbcClient jdbc,
            PasswordEncoder passwords,
            SecurityPolicyPort security,
            AuthenticationPolicyWriteLock policyLock,
            AccountSecurityPort accountSecurity,
            AuthorizationContextBoundaryPort authorizationBoundary,
            PlatformTransactionManager transactionManager) {
        this.profiles = profiles;
        this.emails = emails;
        this.mail = mail;
        this.audit = audit;
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.security = security;
        this.policyLock = policyLock;
        this.accountSecurity = accountSecurity;
        this.authorizationBoundary = authorizationBoundary;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setTimeout(10);
    }

    private SecurityPolicyPort.Policy policy() {
        return security == null ? SecurityPolicyPort.Policy.defaults() : security.currentPolicy();
    }

    public AccountEmailStore.View read(String actor) {
        return emails.read(profiles.find(actor, false).id());
    }

    /**
     * Password verification and SMTP readiness run before the short write transaction. The
     * transaction then rechecks the account/hash and session authorization revision before any
     * verification state or delivery row is written.
     */
    public AccountEmailStore.View request(String actor, Request input) {
        if (input == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidEmail");
        try {
            mail.validateRecipient(input.email());
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidEmail");
        }

        var preflightAccount = profiles.find(actor, false);
        String preflightHash = AccountCredentialPreflight.passwordHash(jdbc, preflightAccount.id());
        AccountCredentialPreflight.requireCurrentPassword(
                passwords, input.currentPassword(), preflightHash);
        // This readiness load can touch configuration storage. Keep it out of the session-locked
        // business transaction and preserve the existing fail-closed behavior for missing SMTP.
        mail.ensureAvailable();

        VerificationToken.Issued token = VerificationToken.issue();
        String[] copy =
                COPY.getOrDefault(
                        input.locale() == null ? "zh-CN" : input.locale(), COPY.get("en"));
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

                            var view =
                                    emails.request(
                                            account.id(), input.version(), input.email(), token);
                            if (!mail.enqueue(
                                    java.util.UUID.randomUUID(),
                                    input.email(),
                                    copy[0],
                                    copy[1] + "\n\n" + token.value(),
                                    policy().verificationCodeExpireSeconds())) {
                                throw new IllegalStateException("Mail task collision");
                            }
                            audit.append(actor, "account.emailVerificationRequested", account.id());
                            return view;
                        }),
                "email request transaction returned no result");
    }

    public boolean confirm(String actor, String token) {
        return Boolean.TRUE.equals(
                writeTransaction.execute(
                        status -> {
                            AccountCredentialPreflight.lockAuthenticationWriteBoundary(
                                    policyLock, authorizationBoundary);
                            var account = profiles.find(actor, true);
                            boolean confirmed = emails.confirm(account.id(), token);
                            if (confirmed && accountSecurity != null)
                                accountSecurity.bumpPrimaryCredentialRevision(account.id());
                            audit.append(
                                    actor,
                                    confirmed
                                            ? "account.emailVerified"
                                            : "account.emailVerificationFailed",
                                    account.id());
                            return confirmed;
                        }));
    }

    private static final Map<String, String[]> COPY =
            Map.of(
                    "zh-CN",
                    new String[] {"验证账号邮箱", "请在账号邮箱验证中粘贴以下验证码，验证码按当前安全策略有效。如非本人操作，请忽略此邮件。"},
                    "zh-TW",
                    new String[] {"驗證帳號信箱", "請在帳號信箱驗證中貼上以下驗證碼，驗證碼依目前安全策略有效。如非本人操作，請忽略此郵件。"},
                    "en",
                    new String[] {
                        "Verify your account email",
                        "Paste this code into account email verification. The code follows the current security policy. Ignore this message if you did not request it."
                    },
                    "ja",
                    new String[] {
                        "アカウントメールの確認",
                        "アカウントのメール確認欄に以下のコードを貼り付けてください。コードは現在のセキュリティポリシーに従います。心当たりがない場合は無視してください。"
                    },
                    "ko",
                    new String[] {
                        "계정 이메일 확인",
                        "계정 이메일 확인란에 아래 코드를 붙여 넣으세요. 코드는 현재 보안 정책을 따릅니다. 요청하지 않았다면 이 메일을 무시하세요."
                    },
                    "fr",
                    new String[] {
                        "Vérifiez votre adresse email",
                        "Collez ce code dans la vérification de votre compte. Le code suit la politique de sécurité actuelle. Ignorez ce message si vous ne l’avez pas demandé."
                    },
                    "es",
                    new String[] {
                        "Verifique el correo de su cuenta",
                        "Pegue este código en la verificación de su cuenta. El código sigue la política de seguridad actual. Ignore el mensaje si no lo solicitó."
                    },
                    "de",
                    new String[] {
                        "E-Mail-Adresse bestätigen",
                        "Fügen Sie diesen Code in die E-Mail-Bestätigung Ihres Kontos ein. Der Code folgt der aktuellen Sicherheitsrichtlinie. Ignorieren Sie unangeforderte Nachrichten."
                    });
}
