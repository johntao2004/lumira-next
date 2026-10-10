package com.lumira.platform.identity;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.MailSender;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.common.SmsSender;
import com.lumira.platform.identity.api.AuthenticationMethodAvailabilityPort;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Anonymous email/SMS login-code flow; only verified, policy-usable contacts receive a code. */
@Service
public class PublicLoginCodeService {
    public record ChallengeView(
            String challengeId, OffsetDateTime expiresAt, OffsetDateTime resendAt) {}

    public record SendResult(ChallengeView challenge, int retryAfterSeconds) {
        public boolean limited() {
            return challenge == null;
        }
    }

    public record CompletionResult(
            com.lumira.platform.identity.api.LoginResponse response, boolean valid) {}

    private record VerifiedLogin(
            String username, PrimaryMethod method, String primaryCredentialRevision) {}

    private static final String PUBLIC_ACTOR = "PUBLIC_LOGIN_CODE";

    private final SecurityPolicyPort security;
    private final AuthenticationPolicyWriteLock policyLock;
    private final PublicAuthChallengeStore challenges;
    private final LoginCodeAccountStore accounts;
    private final PublicAuthCrypto crypto;
    private final PublicAuthRateLimiter rates;
    private final CaptchaProofAccess captcha;
    private final AuthenticationMethodAvailabilityPort methodAvailability;
    private final EffectiveAuthenticationPolicyPort policies;
    private final PrimaryAuthenticationPort primaryAuthentication;
    private final ClientAddressResolver addresses;
    private final MailSender mail;
    private final SmsSender sms;
    private final AuditPort audit;
    private final TransactionTemplate writeTransaction;

    public PublicLoginCodeService(
            SecurityPolicyPort security,
            AuthenticationPolicyWriteLock policyLock,
            PublicAuthChallengeStore challenges,
            LoginCodeAccountStore accounts,
            PublicAuthCrypto crypto,
            PublicAuthRateLimiter rates,
            CaptchaProofAccess captcha,
            AuthenticationMethodAvailabilityPort methodAvailability,
            EffectiveAuthenticationPolicyPort policies,
            PrimaryAuthenticationPort primaryAuthentication,
            ClientAddressResolver addresses,
            MailSender mail,
            SmsSender sms,
            AuditPort audit,
            PlatformTransactionManager transactionManager) {
        this.security = security;
        this.policyLock = policyLock;
        this.challenges = challenges;
        this.accounts = accounts;
        this.crypto = crypto;
        this.rates = rates;
        this.captcha = captcha;
        this.methodAvailability = methodAvailability;
        this.policies = policies;
        this.primaryAuthentication = primaryAuthentication;
        this.addresses = addresses;
        this.mail = mail;
        this.sms = sms;
        this.audit = audit;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setTimeout(10);
    }

    public SendResult requestChallenge(
            PublicAuthRequests.Challenge input, HttpServletRequest request) {
        if (input == null || input.hasUnknownFields())
            throw badRequest("invalidLoginCodeChallenge");
        String channel = channel(input.channel);
        String contact = normalizeContact(channel, input.contact);
        String sessionDigest = crypto.sessionDigest(request.getSession(true).getId());
        String contactDigest = crypto.contactDigest(PublicAuthPurpose.LOGIN, channel, contact);
        String sourceDigest = crypto.sourceDigest(addresses.resolve(request));
        SecurityPolicyPort.Policy securityPolicy = security.currentPolicy();
        if (!rates.allow(
                PublicAuthPurpose.LOGIN, channel, contactDigest, sessionDigest, sourceDigest))
            return new SendResult(null, securityPolicy.verificationCodeCooldownSeconds());

        return writeTransaction.execute(
                status ->
                        requestChallengeInTransaction(
                                input,
                                request,
                                channel,
                                contact,
                                contactDigest,
                                sessionDigest,
                                securityPolicy));
    }

    private SendResult requestChallengeInTransaction(
            PublicAuthRequests.Challenge input,
            HttpServletRequest request,
            String channel,
            String contact,
            String contactDigest,
            String sessionDigest,
            SecurityPolicyPort.Policy securityPolicy) {
        policyLock.lockForAuthentication();
        challenges.lockSessionFlow(PublicAuthPurpose.LOGIN, sessionDigest);
        var gate = captcha.begin(CaptchaProofPort.Action.LOGIN_CODE_SEND, null, null, request);
        captcha.consume(gate, input.captchaProofId, request, null);

        PrimaryMethod method = method(channel);
        var candidate = accounts.findEnabledVerifiedContact(channel, contact).orElse(null);
        if (candidate != null && methodAvailability.available(method)) {
            var locked =
                    accounts.lockEnabledAccountContact(candidate.accountId(), channel).orElse(null);
            String lockedContact =
                    locked == null ? null : normalizeStoredContact(channel, locked.contact());
            if (locked == null
                    || !locked.verified()
                    || candidate.contactVersion() == null
                    || candidate.primaryCredentialRevision() == null
                    || !candidate.contactVersion().equals(locked.contactVersion())
                    || locked.primaryCredentialRevision() == null
                    || !candidate
                            .primaryCredentialRevision()
                            .equals(locked.primaryCredentialRevision())
                    || !contact.equals(lockedContact)) candidate = null;
            else candidate = locked;
        } else {
            candidate = null;
        }
        boolean usable = candidate != null && accountCanUse(candidate.accountId(), method);
        String code = crypto.issueCode();
        UUID challengeId = UUID.randomUUID();
        String encryptedContact = crypto.encryptContact(challengeId, contact);
        String codeDigest =
                crypto.codeDigest(
                        PublicAuthPurpose.LOGIN, challengeId, sessionDigest, contactDigest, code);
        var challenge =
                challenges.create(
                        challengeId,
                        PublicAuthPurpose.LOGIN,
                        channel,
                        sessionDigest,
                        contactDigest,
                        encryptedContact,
                        codeDigest,
                        usable ? candidate.accountId() : null,
                        usable ? candidate.contactVersion() : null,
                        usable ? candidate.primaryCredentialRevision() : null,
                        securityPolicy.verificationCodeExpireSeconds(),
                        securityPolicy.verificationCodeCooldownSeconds());
        if (usable
                && !enqueue(
                        channel,
                        contact,
                        code,
                        input.locale,
                        securityPolicy.verificationCodeExpireSeconds()))
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "loginCodeChannelUnavailable");
        audit.append(PUBLIC_ACTOR, "login.code.challengeRequested", challenge.id());
        return new SendResult(
                new ChallengeView(
                        challenge.id().toString(), challenge.expiresAt(), challenge.resendAt()),
                0);
    }

    public CompletionResult complete(
            PublicAuthRequests.LoginCodeCompletion input,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (input == null
                || input.hasUnknownFields()
                || input.code == null
                || !input.code.matches("\\d{6}")) throw badRequest("invalidLoginCode");
        UUID challengeId = uuid(input.challengeId);
        String sessionDigest = crypto.sessionDigest(request.getSession(true).getId());
        String sourceDigest = crypto.sourceDigest(addresses.resolve(request));
        if (!rates.allowVerification(
                PublicAuthPurpose.LOGIN, sessionDigest, sourceDigest, challengeId.toString()))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "loginCodeLimited");
        VerifiedLogin verified =
                writeTransaction.execute(
                        status ->
                                completeInTransaction(input, request, challengeId, sessionDigest));
        if (verified == null) return new CompletionResult(null, false);

        // The challenge and CAPTCHA proof are committed before the unified authentication path
        // creates a session. The primary-auth provider rechecks the captured credential revision
        // and current policy, so a concurrent password/contact change invalidates this evidence.
        var authentication =
                UsernamePasswordAuthenticationToken.unauthenticated(verified.username(), "");
        var login =
                primaryAuthentication.complete(
                        authentication,
                        verified.method(),
                        verified.primaryCredentialRevision(),
                        request,
                        response);
        return new CompletionResult(login, true);
    }

    private VerifiedLogin completeInTransaction(
            PublicAuthRequests.LoginCodeCompletion input,
            HttpServletRequest request,
            UUID challengeId,
            String sessionDigest) {
        policyLock.lockForAuthentication();
        challenges.lockSessionFlow(PublicAuthPurpose.LOGIN, sessionDigest);
        var challenge = challenges.find(challengeId, sessionDigest, PublicAuthPurpose.LOGIN, true);
        var gate = captcha.begin(CaptchaProofPort.Action.LOGIN_CODE_VERIFY, null, null, request);
        captcha.consume(gate, input.captchaProofId, request, null);
        if (challenge.isEmpty()) return null;

        var current = challenge.get();
        var securityPolicy = security.currentPolicy();
        boolean matches =
                "PENDING".equals(current.state())
                        && current.expiresAt().isAfter(OffsetDateTime.now())
                        && current.attempts() < securityPolicy.verificationFailureLimit()
                        && crypto.matchesCode(
                                PublicAuthPurpose.LOGIN,
                                current.id(),
                                sessionDigest,
                                current.contactDigest(),
                                input.code,
                                current.codeDigest());
        if (!matches) {
            challenges.failAttempt(current.id(), securityPolicy.verificationFailureLimit());
            return null;
        }

        if (current.userId() == null
                || current.contactVersion() == null
                || current.primaryCredentialRevision() == null) {
            challenges.failAttempt(current.id(), securityPolicy.verificationFailureLimit());
            return null;
        }
        var account =
                accounts.lockEnabledAccountContact(current.userId(), current.channel())
                        .orElse(null);
        String verifiedContact =
                account == null
                        ? null
                        : normalizeStoredContact(current.channel(), account.contact());
        String currentContactDigest =
                verifiedContact == null
                        ? null
                        : crypto.contactDigest(
                                PublicAuthPurpose.LOGIN, current.channel(), verifiedContact);
        if (account == null
                || !account.verified()
                || !current.contactVersion().equals(account.contactVersion())
                || !current.primaryCredentialRevision().equals(account.primaryCredentialRevision())
                || !sameDigest(current.contactDigest(), currentContactDigest)) {
            challenges.failAttempt(current.id(), securityPolicy.verificationFailureLimit());
            return null;
        }

        PrimaryMethod method = method(current.channel());
        var effective = policies.resolve(account.accountId());
        if (!methodAvailability.available(method)
                || !effective.allowedMethods().contains(method)
                || !effective.usableMethods().contains(method)) {
            challenges.failAttempt(current.id(), securityPolicy.verificationFailureLimit());
            return null;
        }

        UUID proofId = UUID.randomUUID();
        if (!challenges.verify(current.id(), proofId, securityPolicy.verificationFailureLimit())
                || !challenges.consume(
                        current.id(), proofId, sessionDigest, PublicAuthPurpose.LOGIN)) return null;
        return new VerifiedLogin(
                account.username(), method, current.primaryCredentialRevision().toString());
    }

    private boolean accountCanUse(UUID accountId, PrimaryMethod method) {
        try {
            var effective = policies.resolve(accountId);
            return effective.allowedMethods().contains(method)
                    && effective.usableMethods().contains(method);
        } catch (ResponseStatusException unavailable) {
            return false;
        }
    }

    private boolean enqueue(
            String channel, String contact, String code, String locale, int lifetime) {
        if ("EMAIL".equals(channel)) {
            MailCopy copy = emailCopy(locale);
            return mail.enqueue(
                    UUID.randomUUID(),
                    contact,
                    copy.subject(),
                    copy.body() + "\n\n" + code,
                    lifetime);
        }
        return sms.enqueueVerification(UUID.randomUUID(), contact, code, lifetime);
    }

    private String normalizeContact(String channel, String raw) {
        return PublicAuthContactNormalizer.normalize(
                channel, raw, mail, PublicAuthContactNormalizer.Flow.LOGIN_CODE);
    }

    private static String normalizeStoredContact(String channel, String raw) {
        if (raw == null) return null;
        if ("EMAIL".equals(channel)) return raw.strip().toLowerCase(Locale.ROOT);
        try {
            return AccountPhoneService.normalize(raw);
        } catch (ResponseStatusException invalid) {
            return null;
        }
    }

    private static PrimaryMethod method(String channel) {
        return switch (channel) {
            case "EMAIL" -> PrimaryMethod.EMAIL_OTP;
            case "SMS" -> PrimaryMethod.SMS_OTP;
            default -> throw new IllegalArgumentException("invalidLoginCodeChannel");
        };
    }

    private static String channel(String value) {
        if (!"EMAIL".equals(value) && !"SMS".equals(value))
            throw badRequest("invalidLoginCodeChannel");
        return value;
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException invalid) {
            throw badRequest("invalidLoginCode");
        }
    }

    private static boolean sameDigest(String left, String right) {
        return left != null
                && right != null
                && java.security.MessageDigest.isEqual(
                        left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static MailCopy emailCopy(String locale) {
        return switch (locale == null ? "en" : locale) {
            case "zh-CN" -> new MailCopy("登录验证码", "请在登录页面输入以下验证码。验证码仅用于本次登录：");
            case "zh-TW" -> new MailCopy("登入驗證碼", "請在登入頁面輸入以下驗證碼。驗證碼僅用於本次登入：");
            case "ja" -> new MailCopy("ログインコード", "ログイン画面に次のコードを入力してください。このコードは今回のログインのみ有効です：");
            case "ko" -> new MailCopy("로그인 코드", "로그인 화면에 다음 코드를 입력하세요. 이 코드는 이번 로그인에만 유효합니다:");
            case "fr" ->
                    new MailCopy(
                            "Code de connexion",
                            "Saisissez ce code sur la page de connexion. Il est valable uniquement pour cette connexion :");
            case "es" ->
                    new MailCopy(
                            "Código de inicio de sesión",
                            "Introduzca este código en la página de inicio de sesión. Solo sirve para este inicio:");
            case "de" ->
                    new MailCopy(
                            "Anmeldecode",
                            "Geben Sie diesen Code auf der Anmeldeseite ein. Er gilt nur für diese Anmeldung:");
            default ->
                    new MailCopy(
                            "Login code",
                            "Enter this code on the login page. It is valid only for this sign-in:");
        };
    }

    private record MailCopy(String subject, String body) {}

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
