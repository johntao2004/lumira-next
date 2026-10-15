package com.lumira.platform.identity;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.SmsSender;
import com.lumira.platform.identity.api.AccountSecurityPort;
import com.lumira.platform.identity.api.PasswordContract;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Purpose-, contact-, session- and credential-revision-bound SMS password recovery. */
@Service
public class SmsPasswordRecoveryService
        implements com.lumira.platform.identity.api.SmsPasswordRecoveryPort {
    public record ChallengeView(
            String challengeId, OffsetDateTime expiresAt, OffsetDateTime resendAt) {}

    public record SendResult(ChallengeView challenge, int retryAfterSeconds) {
        public boolean limited() {
            return challenge == null;
        }
    }

    public record VerificationView(String proofId, OffsetDateTime expiresAt) {}

    public record VerifyResult(VerificationView verification, boolean valid) {}

    public record State(
            String state,
            String channel,
            String maskedContact,
            String challengeId,
            String proofId,
            OffsetDateTime expiresAt,
            OffsetDateTime resendAt) {}

    private static final String PUBLIC_ACTOR = "PUBLIC_SMS_PASSWORD_RECOVERY";

    private final SecurityPolicyPort security;
    private final AuthenticationPolicyWriteLock policyLock;
    private final PublicAuthChallengeStore challenges;
    private final LoginCodeAccountStore accounts;
    private final PublicAuthCrypto crypto;
    private final PublicAuthRateLimiter rates;
    private final CaptchaProofAccess captcha;
    private final ClientAddressResolver addresses;
    private final SmsSender sms;
    private final PasswordEncoder passwords;
    private final AccountSecurityPort accountSecurity;
    private final AuditPort audit;
    private final JdbcClient jdbc;
    private final TransactionTemplate writeTransaction;

    public SmsPasswordRecoveryService(
            SecurityPolicyPort security,
            AuthenticationPolicyWriteLock policyLock,
            PublicAuthChallengeStore challenges,
            LoginCodeAccountStore accounts,
            PublicAuthCrypto crypto,
            PublicAuthRateLimiter rates,
            CaptchaProofAccess captcha,
            ClientAddressResolver addresses,
            SmsSender sms,
            PasswordEncoder passwords,
            AccountSecurityPort accountSecurity,
            AuditPort audit,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager) {
        this.security = security;
        this.policyLock = policyLock;
        this.challenges = challenges;
        this.accounts = accounts;
        this.crypto = crypto;
        this.rates = rates;
        this.captcha = captcha;
        this.addresses = addresses;
        this.sms = sms;
        this.passwords = passwords;
        this.accountSecurity = accountSecurity;
        this.audit = audit;
        this.jdbc = jdbc;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setTimeout(10);
    }

    @Override
    public boolean supported() {
        return true;
    }

    public SendResult requestChallenge(
            PublicAuthRequests.RecoveryRequest input, HttpServletRequest request) {
        if (input == null
                || input.hasUnknownFields()
                || !"SMS".equals(input.channel)
                || input.username != null && !input.username.isBlank())
            throw badRequest("invalidRecoveryRequest");
        String contact = normalize(input.contact);
        ensureSmsAvailable();
        String sessionDigest = crypto.sessionDigest(request.getSession(true).getId());
        String contactDigest = crypto.contactDigest(PublicAuthPurpose.SMS_RECOVERY, "SMS", contact);
        String sourceDigest = crypto.sourceDigest(addresses.resolve(request));
        var policy = currentPolicy();
        if (!rates.allow(
                PublicAuthPurpose.SMS_RECOVERY, "SMS", contactDigest, sessionDigest, sourceDigest))
            return new SendResult(null, policy.verificationCodeCooldownSeconds());

        return writeTransaction.execute(
                status ->
                        requestChallengeInTransaction(
                                input, request, contact, contactDigest, sessionDigest));
    }

    private SendResult requestChallengeInTransaction(
            PublicAuthRequests.RecoveryRequest input,
            HttpServletRequest request,
            String contact,
            String contactDigest,
            String sessionDigest) {
        policyLock.lockForAuthentication();
        challenges.lockSessionFlow(PublicAuthPurpose.SMS_RECOVERY, sessionDigest);
        var gate =
                captcha.begin(
                        CaptchaProofPort.Action.PASSWORD_RECOVERY_REQUEST, null, null, request);
        captcha.consume(gate, input.captchaProofId, request, null);
        SecurityPolicyPort.Policy policy = currentPolicy();

        var candidate = accounts.findEnabledVerifiedContact("SMS", contact).orElse(null);
        if (candidate != null) {
            var locked =
                    accounts.lockEnabledAccountContact(candidate.accountId(), "SMS").orElse(null);
            String lockedPhone = locked == null ? null : normalizeStored(locked.contact());
            if (locked == null
                    || !locked.verified()
                    || candidate.contactVersion() == null
                    || candidate.primaryCredentialRevision() == null
                    || locked.contactVersion() == null
                    || locked.primaryCredentialRevision() == null
                    || !candidate.contactVersion().equals(locked.contactVersion())
                    || !candidate
                            .primaryCredentialRevision()
                            .equals(locked.primaryCredentialRevision())
                    || !contact.equals(lockedPhone)) candidate = null;
            else candidate = locked;
        }

        String code = crypto.issueCode();
        UUID challengeId = UUID.randomUUID();
        String ciphertext = crypto.encryptContact(challengeId, contact);
        String codeDigest =
                crypto.codeDigest(
                        PublicAuthPurpose.SMS_RECOVERY,
                        challengeId,
                        sessionDigest,
                        contactDigest,
                        code);
        var challenge =
                challenges.create(
                        challengeId,
                        PublicAuthPurpose.SMS_RECOVERY,
                        "SMS",
                        sessionDigest,
                        contactDigest,
                        ciphertext,
                        codeDigest,
                        candidate == null ? null : candidate.accountId(),
                        candidate == null ? null : candidate.contactVersion(),
                        candidate == null ? null : candidate.primaryCredentialRevision(),
                        policy.verificationCodeExpireSeconds(),
                        policy.verificationCodeCooldownSeconds());
        if (candidate != null
                && !sms.enqueueVerification(
                        UUID.randomUUID(), contact, code, policy.verificationCodeExpireSeconds()))
            throw unavailable("passwordRecoveryChannelUnavailable");
        audit.append(PUBLIC_ACTOR, "account.smsPasswordRecoveryRequested", challenge.id());
        return new SendResult(
                new ChallengeView(
                        challenge.id().toString(), challenge.expiresAt(), challenge.resendAt()),
                0);
    }

    public VerifyResult verifyChallenge(
            PublicAuthRequests.RecoveryVerify input, HttpServletRequest request) {
        if (input == null
                || input.hasUnknownFields()
                || input.code == null
                || !input.code.matches("\\d{6}")) throw badRequest("invalidRecoveryRequest");
        UUID challengeId = uuid(input.challengeId, "invalidRecoveryRequest");
        String sessionDigest = crypto.sessionDigest(request.getSession(true).getId());
        String sourceDigest = crypto.sourceDigest(addresses.resolve(request));
        if (!rates.allowVerification(
                PublicAuthPurpose.SMS_RECOVERY,
                sessionDigest,
                sourceDigest,
                challengeId.toString()))
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "passwordRecoveryLimited");
        return writeTransaction.execute(
                status -> verifyChallengeInTransaction(input, request, challengeId, sessionDigest));
    }

    private VerifyResult verifyChallengeInTransaction(
            PublicAuthRequests.RecoveryVerify input,
            HttpServletRequest request,
            UUID challengeId,
            String sessionDigest) {
        policyLock.lockForAuthentication();
        challenges.lockSessionFlow(PublicAuthPurpose.SMS_RECOVERY, sessionDigest);
        var gate =
                captcha.begin(
                        CaptchaProofPort.Action.PASSWORD_RECOVERY_VERIFY, null, null, request);
        captcha.consume(gate, input.captchaProofId, request, null);
        var challenge =
                challenges.find(challengeId, sessionDigest, PublicAuthPurpose.SMS_RECOVERY, true);
        if (challenge.isEmpty()) return new VerifyResult(null, false);

        var current = challenge.get();
        var policy = currentPolicy();
        boolean matches =
                "PENDING".equals(current.state())
                        && current.expiresAt().isAfter(OffsetDateTime.now())
                        && current.attempts() < policy.verificationFailureLimit()
                        && crypto.matchesCode(
                                PublicAuthPurpose.SMS_RECOVERY,
                                current.id(),
                                sessionDigest,
                                current.contactDigest(),
                                input.code,
                                current.codeDigest());
        if (!matches
                || current.userId() == null
                || current.contactVersion() == null
                || current.primaryCredentialRevision() == null) {
            challenges.failAttempt(current.id(), policy.verificationFailureLimit());
            audit.append(
                    PUBLIC_ACTOR, "account.smsPasswordRecoveryVerificationFailed", current.id());
            return new VerifyResult(null, false);
        }

        var account = accounts.lockEnabledAccountContact(current.userId(), "SMS").orElse(null);
        String phone = account == null ? null : normalizeStored(account.contact());
        String digest =
                phone == null
                        ? null
                        : crypto.contactDigest(PublicAuthPurpose.SMS_RECOVERY, "SMS", phone);
        if (account == null
                || !account.verified()
                || !current.contactVersion().equals(account.contactVersion())
                || !current.primaryCredentialRevision().equals(account.primaryCredentialRevision())
                || !sameDigest(current.contactDigest(), digest)) {
            challenges.failAttempt(current.id(), policy.verificationFailureLimit());
            audit.append(
                    PUBLIC_ACTOR, "account.smsPasswordRecoveryVerificationFailed", current.id());
            return new VerifyResult(null, false);
        }

        UUID proofId = UUID.randomUUID();
        if (!challenges.verify(current.id(), proofId, policy.verificationFailureLimit()))
            return new VerifyResult(null, false);
        var verified =
                challenges
                        .find(current.id(), sessionDigest, PublicAuthPurpose.SMS_RECOVERY, false)
                        .orElseThrow();
        audit.append(PUBLIC_ACTOR, "account.smsPasswordRecoveryVerified", current.id());
        return new VerifyResult(
                new VerificationView(proofId.toString(), verified.proofExpiresAt()), true);
    }

    public State state(HttpServletRequest request) {
        String sessionDigest = crypto.sessionDigest(request.getSession(true).getId());
        var latest = challenges.latest(sessionDigest, PublicAuthPurpose.SMS_RECOVERY);
        if (latest.isEmpty()) return new State("NONE", null, null, null, null, null, null);
        var challenge = latest.get();
        String state = challenge.state();
        if (("PENDING".equals(state) || "VERIFIED".equals(state))
                && !challenge.expiresAt().isAfter(OffsetDateTime.now())) state = "EXPIRED";
        boolean proofActive = challenge.proofActive(OffsetDateTime.now());
        String masked = null;
        try {
            masked = mask(crypto.decryptContact(challenge.id(), challenge.contactCiphertext()));
        } catch (RuntimeException ignored) {
            // A rotated key or corrupt row must never make this status endpoint reveal raw contact
            // data.
        }
        return new State(
                state,
                challenge.channel(),
                masked,
                challenge.id().toString(),
                proofActive ? challenge.proofId().toString() : null,
                proofActive ? challenge.proofExpiresAt() : challenge.expiresAt(),
                challenge.resendAt());
    }

    public boolean confirm(
            PublicAuthRequests.RecoveryConfirmation input, HttpServletRequest request) {
        if (input == null
                || input.hasUnknownFields()
                || input.proofId == null
                || input.proofId.isBlank()
                || input.username != null
                || input.token != null) throw badRequest("invalidRecoveryRequest");
        UUID proofId = uuid(input.proofId, "passwordRecoveryProofInvalid");
        if (!currentPolicy().passwordValid(input.newPassword)) throw badRequest("passwordPolicy");
        String sessionId = request.getSession(true).getId();
        String sessionDigest = crypto.sessionDigest(sessionId);
        var preflight =
                challenges
                        .verifiedByProof(
                                proofId, sessionDigest, PublicAuthPurpose.SMS_RECOVERY, false)
                        .orElseThrow(() -> badRequest("passwordRecoveryProofInvalid"));
        if (!preflight.proofActive(OffsetDateTime.now()) || preflight.userId() == null)
            throw badRequest("passwordRecoveryProofInvalid");
        String sourceDigest = crypto.sourceDigest(addresses.resolve(request));
        if (!rates.allowSmsRecoveryCompletion(
                preflight.contactDigest(), sessionDigest, sourceDigest))
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "passwordRecoveryLimited");

        if (!PasswordHashCapacity.tryAcquire())
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "passwordRecoveryLimited");
        String encoded;
        try {
            encoded = passwords.encode(input.newPassword);
        } finally {
            PasswordHashCapacity.release();
        }

        return Boolean.TRUE.equals(
                writeTransaction.execute(
                        status ->
                                confirmInTransaction(
                                        input, request, proofId, sessionDigest, encoded)));
    }

    private boolean confirmInTransaction(
            PublicAuthRequests.RecoveryConfirmation input,
            HttpServletRequest request,
            UUID proofId,
            String sessionDigest,
            String encodedPassword) {
        policyLock.lockForAuthentication();
        challenges.lockSessionFlow(PublicAuthPurpose.SMS_RECOVERY, sessionDigest);
        var challenge =
                challenges
                        .verifiedByProof(
                                proofId, sessionDigest, PublicAuthPurpose.SMS_RECOVERY, true)
                        .orElse(null);
        if (challenge == null
                || !challenge.proofActive(OffsetDateTime.now())
                || challenge.userId() == null) return false;

        var account = accounts.lockEnabledAccountContact(challenge.userId(), "SMS").orElse(null);
        String phone = account == null ? null : normalizeStored(account.contact());
        String digest =
                phone == null
                        ? null
                        : crypto.contactDigest(PublicAuthPurpose.SMS_RECOVERY, "SMS", phone);
        if (account == null
                || !account.verified()
                || challenge.contactVersion() == null
                || challenge.primaryCredentialRevision() == null
                || !challenge.contactVersion().equals(account.contactVersion())
                || !challenge
                        .primaryCredentialRevision()
                        .equals(account.primaryCredentialRevision())
                || !sameDigest(challenge.contactDigest(), digest)) return false;

        var gate =
                captcha.begin(
                        CaptchaProofPort.Action.PASSWORD_RECOVERY_CONFIRM,
                        account.accountId(),
                        null,
                        request);
        captcha.consume(gate, input.captchaProofId, request, null);
        var policy = currentPolicy();
        if (!policy.passwordValid(input.newPassword)) throw badRequest("passwordPolicy");

        String existingHash =
                jdbc.sql("SELECT password_hash FROM platform_user WHERE id=:id")
                        .param("id", account.accountId())
                        .query(String.class)
                        .single();
        if (PasswordContract.compatibleWithStoredHash(input.newPassword, existingHash)
                && passwords.matches(input.newPassword, existingHash))
            throw badRequest("passwordMustDiffer");

        if (!challenges.consume(
                challenge.id(), proofId, sessionDigest, PublicAuthPurpose.SMS_RECOVERY))
            return false;
        jdbc.sql("UPDATE platform_user SET password_hash=:password WHERE id=:id")
                .param("id", account.accountId())
                .param("password", encodedPassword)
                .update();
        accountSecurity.bumpPrimaryCredentialRevision(account.accountId());
        jdbc.sql("DELETE FROM platform_password_change_requirement WHERE user_id=:id")
                .param("id", account.accountId())
                .update();
        IdentityConfiguration.completeBootstrapRequirement(jdbc, account.accountId());
        accountSecurity.revokeSessions(account.accountId(), null);
        audit.append(PUBLIC_ACTOR, "account.smsPasswordRecovered", account.accountId());
        return true;
    }

    private void ensureSmsAvailable() {
        if (!sms.ready()) throw unavailable("passwordRecoveryChannelUnavailable");
    }

    private SecurityPolicyPort.Policy currentPolicy() {
        SecurityPolicyPort.Policy policy = security.currentPolicy();
        return policy == null ? SecurityPolicyPort.Policy.defaults() : policy;
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 100)
            throw badRequest("invalidRecoveryContact");
        try {
            return AccountPhoneService.normalize(raw);
        } catch (ResponseStatusException invalid) {
            throw badRequest("invalidRecoveryContact");
        }
    }

    private static String normalizeStored(String raw) {
        if (raw == null) return null;
        try {
            return AccountPhoneService.normalize(raw);
        } catch (ResponseStatusException invalid) {
            return null;
        }
    }

    private static String mask(String phone) {
        return phone == null
                ? null
                : phone.length() < 5 ? "***" : "***" + phone.substring(phone.length() - 4);
    }

    private static boolean sameDigest(String left, String right) {
        if (left == null || right == null) return false;
        return java.security.MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static UUID uuid(String value, String code) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException invalid) {
            throw badRequest(code);
        }
    }

    private static ResponseStatusException badRequest(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private static ResponseStatusException unavailable(String code) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, code);
    }
}
