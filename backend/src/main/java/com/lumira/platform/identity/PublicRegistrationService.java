package com.lumira.platform.identity;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.MailSender;
import com.lumira.platform.common.RegistrationContentPolicyPort;
import com.lumira.platform.common.RegistrationRolePort;
import com.lumira.platform.common.SmsSender;
import com.lumira.platform.identity.api.AccountProvisioningPort;
import com.lumira.platform.identity.api.AgreementSnapshotPort;
import com.lumira.platform.identity.api.MaintenancePolicyPort;
import com.lumira.platform.identity.api.PasswordContract;
import com.lumira.platform.identity.api.ProfileRegistrationPort;
import com.lumira.platform.identity.api.RegistrationPolicyPort;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import com.lumira.platform.identity.api.WechatRegistrationBindingPort;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Public registration orchestration; account, contact, role, profile and agreement writes share one
 * transaction.
 */
@Service
public class PublicRegistrationService {
    public record PasswordRules(
            int minLength,
            int maxBytes,
            boolean requireUppercase,
            boolean requireLowercase,
            boolean requireSpecialCharacter,
            boolean allowConsecutiveCharacters) {}

    public record CodePolicy(int expiresInSeconds, int cooldownSeconds) {}

    public record AgreementsView(
            long version, String userAgreementMarkdown, String privacyAgreementMarkdown) {}

    public record Config(
            boolean available,
            String reason,
            List<String> channels,
            long policyVersion,
            PasswordRules passwordRules,
            CodePolicy codePolicy,
            AgreementsView agreements) {}

    public record ChallengeView(
            String challengeId, OffsetDateTime expiresAt, OffsetDateTime resendAt) {}

    public record VerificationView(String proofId, OffsetDateTime expiresAt) {}

    public record RegistrationReceipt(
            String receiptId,
            String username,
            String nextPath,
            WechatRegistrationBindingPort.BindingResult wechatBinding) {}

    public record RegistrationState(
            String state,
            String channel,
            String maskedContact,
            String challengeId,
            String proofId,
            OffsetDateTime expiresAt,
            OffsetDateTime resendAt,
            RegistrationReceipt receipt) {}

    public record SendResult(ChallengeView challenge, int retryAfterSeconds) {
        public boolean limited() {
            return challenge == null;
        }
    }

    public record VerifyResult(VerificationView verification, boolean valid) {}

    private static final String PUBLIC_ACTOR = "PUBLIC_REGISTRATION";
    private static final Set<String> REGISTRATION_USERNAME_RESERVED =
            Set.of("admin", "administrator", "root", "system", "support");

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final RegistrationPolicyPort registrationPolicy;
    private final AuthenticationPolicyWriteLock authenticationPolicyLock;
    private final RegistrationRolePort roles;
    private final AgreementSnapshotPort agreements;
    private final AccountProvisioningPort accounts;
    private final ProfileRegistrationPort profileRegistration;
    private final ObjectProvider<WechatRegistrationBindingPort> wechatBindings;
    private final ObjectProvider<RegistrationContentPolicyPort> contentPolicies;
    private final MaintenancePolicyPort maintenance;
    private final SecurityPolicyPort security;
    private final PublicAuthChallengeStore challenges;
    private final PublicAuthCrypto crypto;
    private final PublicAuthRateLimiter rates;
    private final CaptchaProofAccess captcha;
    private final ClientAddressResolver addresses;
    private final MailSender mail;
    private final SmsSender sms;
    private final ObjectProvider<com.lumira.platform.identity.api.SmsPasswordRecoveryPort>
            smsRecovery;
    private final AuditPort audit;
    private final TransactionTemplate completionTransaction;

    public PublicRegistrationService(
            JdbcClient jdbc,
            ObjectMapper mapper,
            RegistrationPolicyPort registrationPolicy,
            AuthenticationPolicyWriteLock authenticationPolicyLock,
            RegistrationRolePort roles,
            AgreementSnapshotPort agreements,
            AccountProvisioningPort accounts,
            ProfileRegistrationPort profileRegistration,
            ObjectProvider<WechatRegistrationBindingPort> wechatBindings,
            ObjectProvider<RegistrationContentPolicyPort> contentPolicies,
            MaintenancePolicyPort maintenance,
            SecurityPolicyPort security,
            PublicAuthChallengeStore challenges,
            PublicAuthCrypto crypto,
            PublicAuthRateLimiter rates,
            CaptchaProofAccess captcha,
            ClientAddressResolver addresses,
            MailSender mail,
            SmsSender sms,
            ObjectProvider<com.lumira.platform.identity.api.SmsPasswordRecoveryPort> smsRecovery,
            AuditPort audit,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.registrationPolicy = registrationPolicy;
        this.authenticationPolicyLock = authenticationPolicyLock;
        this.roles = roles;
        this.agreements = agreements;
        this.accounts = accounts;
        this.profileRegistration = profileRegistration;
        this.wechatBindings = wechatBindings;
        this.contentPolicies = contentPolicies;
        this.maintenance = maintenance;
        this.security = security;
        this.challenges = challenges;
        this.crypto = crypto;
        this.rates = rates;
        this.captcha = captcha;
        this.addresses = addresses;
        this.mail = mail;
        this.sms = sms;
        this.smsRecovery = smsRecovery;
        this.audit = audit;
        this.completionTransaction = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public Config config() {
        var policy = registrationPolicy.current();
        var agreement = agreements.current();
        var securityPolicy = security.currentPolicy();
        var channelNames = new ArrayList<String>();
        String reason = null;
        if (!policy.enabled()) {
            reason = "REGISTRATION_CLOSED";
        } else if (!maintenance.publicRegistrationAvailable()) {
            reason = "MAINTENANCE";
        } else if (policy.defaultRoleId() == null
                || !roles.candidate(policy.defaultRoleId()).eligible()
                || !agreement.complete()
                || !captcha.actionsAvailable(
                        Set.of(
                                CaptchaProofPort.Action.REGISTRATION_SEND,
                                CaptchaProofPort.Action.REGISTRATION_VERIFY,
                                CaptchaProofPort.Action.REGISTRATION_COMPLETE),
                        policy.defaultRoleId())) {
            reason = "REGISTRATION_UNAVAILABLE";
        } else {
            for (var channel : policy.channels().stream().sorted().toList()) {
                if (channelAvailable(channel)) channelNames.add(channel.name());
            }
            if (channelNames.isEmpty()) reason = "REGISTRATION_UNAVAILABLE";
        }
        return new Config(
                reason == null,
                reason,
                reason == null ? List.copyOf(channelNames) : List.of(),
                policy.version(),
                new PasswordRules(
                        securityPolicy.passwordMinLength(),
                        PasswordContract.MAX_PASSWORD_BYTES,
                        securityPolicy.passwordRequireUppercase(),
                        securityPolicy.passwordRequireLowercase(),
                        securityPolicy.passwordRequireSpecialCharacter(),
                        securityPolicy.passwordAllowConsecutiveCharacters()),
                new CodePolicy(
                        securityPolicy.verificationCodeExpireSeconds(),
                        securityPolicy.verificationCodeCooldownSeconds()),
                new AgreementsView(
                        agreement.version(),
                        agreement.userAgreementMarkdown(),
                        agreement.privacyAgreementMarkdown()));
    }

    public SendResult requestChallenge(
            PublicAuthRequests.Challenge input, HttpServletRequest request) {
        if (input == null || input.hasUnknownFields())
            throw badRequest("invalidRegistrationChallenge");
        String channel = channel(input.channel);
        String contact = normalizeContact(channel, input.contact);
        String sessionDigest = crypto.sessionDigest(request.getSession(true).getId());
        String contactDigest =
                crypto.contactDigest(PublicAuthPurpose.REGISTRATION, channel, contact);
        String sourceDigest = crypto.sourceDigest(addresses.resolve(request));
        var policy = registrationPolicy.current();
        requireAvailable(policy, channel, CaptchaProofPort.Action.REGISTRATION_SEND);
        var securityPolicy = security.currentPolicy();
        if (!rates.allow(
                PublicAuthPurpose.REGISTRATION,
                channel,
                contactDigest,
                sessionDigest,
                sourceDigest))
            return new SendResult(null, securityPolicy.verificationCodeCooldownSeconds());
        return completionTransaction.execute(
                status ->
                        requestChallengeInTransaction(
                                input, request, channel, contact, contactDigest, sessionDigest));
    }

    private SendResult requestChallengeInTransaction(
            PublicAuthRequests.Challenge input,
            HttpServletRequest request,
            String channel,
            String contact,
            String contactDigest,
            String sessionDigest) {
        authenticationPolicyLock.lockForAuthentication();
        var policy = registrationPolicy.lockCurrent();
        requireAvailable(policy, channel, CaptchaProofPort.Action.REGISTRATION_SEND);
        var securityPolicy = security.currentPolicy();
        var gate =
                captcha.begin(
                        CaptchaProofPort.Action.REGISTRATION_SEND,
                        null,
                        policy.defaultRoleId(),
                        request);
        captcha.consume(gate, input.captchaProofId, request, null);

        String code = crypto.issueCode();
        UUID challengeId = UUID.randomUUID();
        String encryptedContact = crypto.encryptContact(challengeId, contact);
        String codeDigest =
                crypto.codeDigest(
                        PublicAuthPurpose.REGISTRATION,
                        challengeId,
                        sessionDigest,
                        contactDigest,
                        code);
        var challenge =
                challenges.create(
                        challengeId,
                        PublicAuthPurpose.REGISTRATION,
                        channel,
                        sessionDigest,
                        contactDigest,
                        encryptedContact,
                        codeDigest,
                        null,
                        null,
                        null,
                        securityPolicy.verificationCodeExpireSeconds(),
                        securityPolicy.verificationCodeCooldownSeconds());
        if (!enqueue(
                channel,
                contact,
                code,
                input.locale,
                securityPolicy.verificationCodeExpireSeconds()))
            throw unavailable("registrationChannelUnavailable");
        audit.append(PUBLIC_ACTOR, "registration.challengeRequested", challenge.id());
        return new SendResult(challengeView(challenge), 0);
    }

    public VerifyResult verifyChallenge(
            PublicAuthRequests.Verify input, HttpServletRequest request) {
        if (input == null || input.hasUnknownFields())
            throw badRequest("invalidRegistrationChallenge");
        UUID challengeId = uuid(input.challengeId, "invalidRegistrationChallenge");
        String sessionDigest = crypto.sessionDigest(request.getSession(true).getId());
        String sourceDigest = crypto.sourceDigest(addresses.resolve(request));
        if (!rates.allowVerification(
                PublicAuthPurpose.REGISTRATION,
                sessionDigest,
                sourceDigest,
                challengeId.toString()))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "registrationLimited");
        return completionTransaction.execute(
                status -> verifyChallengeInTransaction(input, request, challengeId, sessionDigest));
    }

    private VerifyResult verifyChallengeInTransaction(
            PublicAuthRequests.Verify input,
            HttpServletRequest request,
            UUID challengeId,
            String sessionDigest) {
        authenticationPolicyLock.lockForAuthentication();
        var policy = registrationPolicy.lockCurrent();
        if (!policy.enabled() || !maintenance.publicRegistrationAvailable())
            throw unavailable(policy.enabled() ? "registrationUnavailable" : "registrationClosed");
        var gate =
                captcha.begin(
                        CaptchaProofPort.Action.REGISTRATION_VERIFY,
                        null,
                        policy.defaultRoleId(),
                        request);
        var challenge =
                challenges.find(challengeId, sessionDigest, PublicAuthPurpose.REGISTRATION, true);
        if (challenge.isEmpty()) return new VerifyResult(null, false);
        var current = challenge.get();
        if (!policy.channels().contains(RegistrationPolicyPort.Channel.valueOf(current.channel())))
            return new VerifyResult(null, false);
        captcha.consume(gate, input.captchaProofId, request, null);
        var securityPolicy = security.currentPolicy();
        boolean matches =
                "PENDING".equals(current.state())
                        && current.expiresAt().isAfter(OffsetDateTime.now())
                        && current.attempts() < securityPolicy.verificationFailureLimit()
                        && crypto.matchesCode(
                                PublicAuthPurpose.REGISTRATION,
                                current.id(),
                                sessionDigest,
                                current.contactDigest(),
                                input.code,
                                current.codeDigest());
        if (!matches) {
            challenges.failAttempt(current.id(), securityPolicy.verificationFailureLimit());
            audit.append(PUBLIC_ACTOR, "registration.challengeFailed", current.id());
            return new VerifyResult(null, false);
        }
        UUID proofId = UUID.randomUUID();
        if (!challenges.verify(current.id(), proofId, securityPolicy.verificationFailureLimit()))
            return new VerifyResult(null, false);
        var verified =
                challenges
                        .find(current.id(), sessionDigest, PublicAuthPurpose.REGISTRATION, false)
                        .orElseThrow();
        audit.append(PUBLIC_ACTOR, "registration.challengeVerified", current.id());
        return new VerifyResult(
                new VerificationView(proofId.toString(), verified.proofExpiresAt()), true);
    }

    @Transactional(readOnly = true)
    public RegistrationState state(HttpServletRequest request) {
        String sessionDigest = crypto.sessionDigest(request.getSession(true).getId());
        Optional<PublicAuthChallengeStore.Challenge> latest =
                challenges.latest(sessionDigest, PublicAuthPurpose.REGISTRATION);
        if (latest.isEmpty()) return emptyState();
        var challenge = latest.get();
        String status = challenge.state();
        if (("PENDING".equals(status) || "VERIFIED".equals(status))
                && !challenge.expiresAt().isAfter(OffsetDateTime.now())) status = "EXPIRED";
        boolean activeProof = challenge.proofActive(OffsetDateTime.now());
        RegistrationReceipt receipt = null;
        if ("CONSUMED".equals(challenge.state()) && challenge.proofId() != null) {
            receipt =
                    challenges
                            .completionForProof(challenge.proofId(), sessionDigest)
                            .map(this::parseReceipt)
                            .orElse(null);
        }
        String masked = null;
        try {
            masked =
                    mask(
                            challenge.channel(),
                            crypto.decryptContact(challenge.id(), challenge.contactCiphertext()));
        } catch (RuntimeException ignored) {
            // A key rotation or damaged ciphertext must not reveal unmasked data in the status
            // response.
        }
        return new RegistrationState(
                status,
                challenge.channel(),
                masked,
                challenge.id().toString(),
                activeProof ? challenge.proofId().toString() : null,
                activeProof ? challenge.proofExpiresAt() : challenge.expiresAt(),
                challenge.resendAt(),
                receipt);
    }

    public RegistrationReceipt complete(
            PublicAuthRequests.RegistrationCompletion input, HttpServletRequest request) {
        validateCompletion(input);
        UUID proofId = uuid(input.proofId, "registrationProofInvalid");
        UUID requestId = uuid(input.requestId, "invalidRegistrationRequest");
        String sessionDigest = crypto.sessionDigest(request.getSession(true).getId());
        Map<String, String> values = input.normalizedProfileValues();
        String fingerprint =
                crypto.fingerprint("registration-complete", completionBytes(input, values));

        var prior = challenges.completion(requestId);
        if (prior.isPresent()) return replay(prior.get(), sessionDigest, proofId, fingerprint);

        // Read-only proof preflight avoids performing Argon2 for arbitrary anonymous identifiers.
        var preflight =
                challenges
                        .verifiedByProof(
                                proofId, sessionDigest, PublicAuthPurpose.REGISTRATION, false)
                        .orElseThrow(() -> badRequest("registrationProofInvalid"));
        if (!preflight.proofActive(OffsetDateTime.now()))
            throw badRequest("registrationProofExpired");
        if (!security.currentPolicy().passwordValid(input.password))
            throw badRequest("passwordPolicy");
        String sourceDigest = crypto.sourceDigest(addresses.resolve(request));
        if (!rates.allowRegistrationCompletion(
                preflight.contactDigest(), sessionDigest, sourceDigest))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "registrationLimited");
        if (!PasswordHashCapacity.tryAcquire())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "registrationLimited");
        AccountProvisioningPort.PreparedPassword preparedPassword;
        try {
            preparedPassword = accounts.preparePassword(input.password);
        } finally {
            PasswordHashCapacity.release();
        }

        return completionTransaction.execute(
                status ->
                        completeInTransaction(
                                input,
                                request,
                                proofId,
                                requestId,
                                sessionDigest,
                                preflight.channel(),
                                values,
                                fingerprint,
                                preparedPassword));
    }

    private RegistrationReceipt completeInTransaction(
            PublicAuthRequests.RegistrationCompletion input,
            HttpServletRequest request,
            UUID proofId,
            UUID requestId,
            String sessionDigest,
            String preflightChannel,
            Map<String, String> values,
            String fingerprint,
            AccountProvisioningPort.PreparedPassword preparedPassword) {
        // Password hashing and request rate limiting are complete before acquiring transaction
        // locks.
        authenticationPolicyLock.lockForAuthentication();
        var policy = registrationPolicy.lockCurrent();

        // Same-request callers serialize on the session flow lock. Check the global idempotency
        // record before policy, CAPTCHA, or proof validation so a committed result remains
        // replayable
        // even after its proof was consumed or registration settings later changed.
        challenges.lockSessionFlow(PublicAuthPurpose.REGISTRATION, sessionDigest);
        var replayed = challenges.completion(requestId);
        if (replayed.isPresent())
            return replay(replayed.get(), sessionDigest, proofId, fingerprint);

        if (input.policyVersion != policy.version()) throw conflict("registrationPolicyChanged");
        if (!security.currentPolicy().passwordValid(input.password))
            throw badRequest("passwordPolicy");
        requireAvailable(policy, preflightChannel, CaptchaProofPort.Action.REGISTRATION_COMPLETE);
        UUID roleId = policy.defaultRoleId();
        roles.lockAndRequireEligible(roleId);
        checkRegistrationContent(roleId, input.username, "registration.username");
        checkRegistrationContent(roleId, input.displayName, "registration.displayName");
        var gate =
                captcha.begin(CaptchaProofPort.Action.REGISTRATION_COMPLETE, null, roleId, request);
        var agreementSnapshot = agreements.capture(input.agreementVersion);

        var locked =
                challenges
                        .verifiedByProof(
                                proofId, sessionDigest, PublicAuthPurpose.REGISTRATION, true)
                        .orElseThrow(() -> badRequest("registrationProofInvalid"));
        if (!locked.proofActive(OffsetDateTime.now())) throw badRequest("registrationProofExpired");
        if (!policy.channels().contains(RegistrationPolicyPort.Channel.valueOf(locked.channel())))
            throw unavailable("registrationUnavailable");
        captcha.consume(gate, input.captchaProofId, request, null);

        // A competing request with the same proof may have committed while this request held no row
        // lock.
        var concurrent = challenges.completion(requestId);
        if (concurrent.isPresent())
            return replay(concurrent.get(), sessionDigest, proofId, fingerprint);
        if (!challenges.reserveCompletion(
                requestId, sessionDigest, locked.id(), proofId, fingerprint)) {
            var saved =
                    challenges
                            .completion(requestId)
                            .orElseThrow(() -> conflict("registrationRequestConflict"));
            return replay(saved, sessionDigest, proofId, fingerprint);
        }

        String contact = crypto.decryptContact(locked.id(), locked.contactCiphertext());
        UUID accountId =
                accounts.provision(
                        input.username.strip(), input.displayName.strip(), preparedPassword);
        if (!storeVerifiedContact(accountId, locked.channel(), contact))
            throw conflict("registrationContactAlreadyUsed");
        roles.assignToNewAccount(accountId, roleId);
        if (!values.isEmpty()) {
            if (input.profileSchemaVersion == null) throw badRequest("invalidProfileValues");
            profileRegistration.validateAndStoreByAccount(
                    accountId, input.profileSchemaVersion, values);
        }
        jdbc.sql(
                        "INSERT INTO platform_account_agreement_acceptance(user_id,snapshot_id,request_id) VALUES(:user,:snapshot,:request)")
                .param("user", accountId)
                .param("snapshot", agreementSnapshot.id())
                .param("request", requestId)
                .update();
        var binding =
                consumeOptionalWechat(
                        input.wechatRegistrationProofId, proofId.toString(), accountId, request);
        var receipt =
                new RegistrationReceipt(
                        UUID.randomUUID().toString(),
                        input.username.strip(),
                        "/user/login",
                        binding);
        if (!challenges.consume(
                locked.id(), proofId, sessionDigest, PublicAuthPurpose.REGISTRATION))
            throw badRequest("registrationProofExpired");
        if (!challenges.finishCompletion(requestId, accountId, mapper.writeValueAsString(receipt)))
            throw conflict("registrationRequestConflict");
        audit.append(PUBLIC_ACTOR, "account.registered", accountId);
        return receipt;
    }

    private void validateCompletion(PublicAuthRequests.RegistrationCompletion input) {
        if (input == null || input.hasUnknownFields())
            throw badRequest("invalidRegistrationRequest");
        if (blankOrLong(input.proofId, 36)
                || !validUsername(input.username)
                || input.displayName == null
                || input.displayName.isBlank()
                || input.displayName.strip().length() > 200
                || input.password == null
                || input.policyVersion == null
                || input.agreementVersion == null
                || input.policyVersion < 1
                || input.agreementVersion < 1
                || !Boolean.TRUE.equals(input.acceptAgreements)
                || blankOrLong(input.requestId, 36)
                || input.wechatRegistrationProofId != null
                        && blankOrLong(input.wechatRegistrationProofId, 200))
            throw badRequest("invalidRegistrationRequest");
        Map<String, String> values = input.profileValues;
        if (values == null) return;
        if (values.size() > 20
                || values.entrySet().stream()
                        .anyMatch(
                                e ->
                                        e.getKey() == null
                                                || e.getValue() == null
                                                || e.getKey().length() > 128
                                                || e.getValue()
                                                                .getBytes(StandardCharsets.UTF_8)
                                                                .length
                                                        > 8_000))
            throw badRequest("invalidProfileValues");
    }

    private RegistrationReceipt replay(
            PublicAuthChallengeStore.Completion completion,
            String sessionDigest,
            UUID proofId,
            String fingerprint) {
        if (!completion.proofId().equals(proofId)
                || !MessageDigest.isEqual(
                        completion.fingerprint().getBytes(StandardCharsets.US_ASCII),
                        fingerprint.getBytes(StandardCharsets.US_ASCII)))
            throw conflict("registrationRequestConflict");
        var owner =
                challenges.find(
                        completion.challengeId(),
                        sessionDigest,
                        PublicAuthPurpose.REGISTRATION,
                        false);
        if (owner.isEmpty() || !owner.get().sessionDigest().equals(sessionDigest))
            throw badRequest("registrationProofInvalid");
        if (completion.receipt() == null) throw conflict("registrationRequestPending");
        return parseReceipt(completion);
    }

    private RegistrationReceipt parseReceipt(PublicAuthChallengeStore.Completion completion) {
        try {
            return mapper.readValue(completion.receipt(), RegistrationReceipt.class);
        } catch (RuntimeException error) {
            throw unavailable("registrationReceiptUnavailable");
        }
    }

    static byte[] completionBytes(
            PublicAuthRequests.RegistrationCompletion input, Map<String, String> values) {
        var parts = new ArrayList<String>();
        parts.add(input.proofId);
        parts.add(input.username.strip());
        parts.add(input.displayName.strip());
        parts.add(input.password);
        parts.add(String.valueOf(input.policyVersion));
        parts.add(String.valueOf(input.agreementVersion));
        parts.add(String.valueOf(input.acceptAgreements));
        parts.add(input.requestId);
        parts.add(String.valueOf(input.profileSchemaVersion));
        parts.add(input.wechatRegistrationProofId == null ? "" : input.wechatRegistrationProofId);
        var sorted = new TreeMap<>(values);
        sorted.forEach(
                (key, value) -> {
                    parts.add(key);
                    parts.add(value);
                });
        var output = new java.io.ByteArrayOutputStream();
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            output.writeBytes(bytes);
        }
        return output.toByteArray();
    }

    private void checkRegistrationContent(UUID roleId, String text, String source) {
        if (text == null || text.isBlank()) return;
        var policy = contentPolicies.getIfAvailable();
        if (policy != null && policy.blocked(roleId, text, source))
            throw badRequest("sensitiveContent");
    }

    private WechatRegistrationBindingPort.BindingResult consumeOptionalWechat(
            String proofId, String registrationProof, UUID accountId, HttpServletRequest request) {
        if (proofId == null || proofId.isBlank())
            return WechatRegistrationBindingPort.BindingResult.notRequested();
        var binding = wechatBindings.getIfAvailable();
        if (binding == null)
            return WechatRegistrationBindingPort.BindingResult.failed(
                    "wechatRegistrationUnavailable");
        var session = request.getSession(false);
        String sessionDigest = session == null ? null : crypto.sessionDigest(session.getId());
        var result =
                binding.consumeForRegistration(
                        proofId, registrationProof, accountId, sessionDigest);
        return result == null
                ? WechatRegistrationBindingPort.BindingResult.failed("wechatRegistrationFailed")
                : result;
    }

    private boolean storeVerifiedContact(UUID accountId, String channel, String contact) {
        if ("EMAIL".equals(channel)) {
            return jdbc.sql(
                            "INSERT INTO platform_account_email(user_id,email,verified_at,version) VALUES(:user,:contact,now(),1) ON CONFLICT DO NOTHING RETURNING user_id")
                    .param("user", accountId)
                    .param("contact", contact)
                    .query(UUID.class)
                    .optional()
                    .isPresent();
        }
        return jdbc.sql(
                        "INSERT INTO platform_account_phone(user_id,phone,verified_at,version) VALUES(:user,:contact,now(),1) ON CONFLICT DO NOTHING RETURNING user_id")
                .param("user", accountId)
                .param("contact", contact)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    private void requireAvailable(
            RegistrationPolicyPort.Policy policy, String channel, CaptchaProofPort.Action action) {
        if (!policy.enabled()) throw unavailable("registrationClosed");
        if (!maintenance.publicRegistrationAvailable()) throw unavailable("maintenanceActive");
        if (!policy.channels().contains(RegistrationPolicyPort.Channel.valueOf(channel)))
            throw unavailable("registrationChannelUnavailable");
        if (policy.defaultRoleId() == null || !roles.candidate(policy.defaultRoleId()).eligible())
            throw unavailable("registrationUnavailable");
        if (!agreements.current().complete()) throw unavailable("registrationUnavailable");
        if (!captcha.actionAvailable(action, null, policy.defaultRoleId()))
            throw unavailable("captchaUnavailable");
        if (!channelAvailable(RegistrationPolicyPort.Channel.valueOf(channel)))
            throw unavailable("registrationChannelUnavailable");
    }

    private boolean channelAvailable(RegistrationPolicyPort.Channel channel) {
        if (channel == RegistrationPolicyPort.Channel.EMAIL) return mail.ready();
        var recovery = smsRecovery.getIfAvailable();
        return recovery != null && recovery.supported() && sms.ready();
    }

    private String normalizeContact(String channel, String raw) {
        return PublicAuthContactNormalizer.normalize(
                channel, raw, mail, PublicAuthContactNormalizer.Flow.REGISTRATION);
    }

    private boolean enqueue(
            String channel, String contact, String code, String locale, int lifetime) {
        var copy = emailCopy(locale);
        return "EMAIL".equals(channel)
                ? mail.enqueue(
                        UUID.randomUUID(),
                        contact,
                        copy.subject(),
                        copy.body() + "\n\n" + code,
                        lifetime)
                : sms.enqueueVerification(UUID.randomUUID(), contact, code, lifetime);
    }

    private record MailCopy(String subject, String body) {}

    private static MailCopy emailCopy(String locale) {
        return switch (locale == null ? "en" : locale) {
            case "zh-CN" -> new MailCopy("注册验证码", "请在注册页面输入以下验证码。验证码仅用于本次注册：");
            case "zh-TW" -> new MailCopy("註冊驗證碼", "請在註冊頁面輸入以下驗證碼。驗證碼僅用於本次註冊：");
            case "ja" -> new MailCopy("登録コード", "登録ページに次のコードを入力してください。このコードはこの登録のみ有効です：");
            case "ko" -> new MailCopy("가입 인증 코드", "가입 화면에 다음 코드를 입력하세요. 이 코드는 이번 가입에만 유효합니다:");
            case "fr" ->
                    new MailCopy(
                            "Code d’inscription",
                            "Saisissez ce code sur la page d’inscription. Il est valable uniquement pour cette inscription :");
            case "es" ->
                    new MailCopy(
                            "Código de registro",
                            "Introduzca este código en la página de registro. Solo sirve para este registro:");
            case "de" ->
                    new MailCopy(
                            "Registrierungscode",
                            "Geben Sie diesen Code auf der Registrierungsseite ein. Er gilt nur für diese Registrierung:");
            default ->
                    new MailCopy(
                            "Registration code",
                            "Enter this code on the registration page. It is valid only for this registration:");
        };
    }

    private static String channel(String value) {
        if (!"EMAIL".equals(value) && !"SMS".equals(value))
            throw badRequest("invalidRegistrationChannel");
        return value;
    }

    private static String mask(String channel, String contact) {
        if (contact == null) return null;
        if ("EMAIL".equals(channel)) {
            int at = contact.indexOf('@');
            if (at < 1) return "***";
            return contact.substring(0, 1) + "***" + contact.substring(at);
        }
        return contact.length() < 5 ? "***" : "***" + contact.substring(contact.length() - 4);
    }

    private static boolean validUsername(String value) {
        return value != null
                && value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{2,99}")
                && !REGISTRATION_USERNAME_RESERVED.contains(value.toLowerCase(Locale.ROOT));
    }

    private static boolean blankOrLong(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }

    private static UUID uuid(String value, String code) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException error) {
            throw badRequest(code);
        }
    }

    private static RegistrationState emptyState() {
        return new RegistrationState("NONE", null, null, null, null, null, null, null);
    }

    private static ChallengeView challengeView(PublicAuthChallengeStore.Challenge challenge) {
        return new ChallengeView(
                challenge.id().toString(), challenge.expiresAt(), challenge.resendAt());
    }

    private static ResponseStatusException badRequest(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private static ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    private static ResponseStatusException unavailable(String code) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, code);
    }
}
