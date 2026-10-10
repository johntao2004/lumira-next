package com.lumira.platform.identity;

import com.lumira.platform.common.AuthenticationCapabilityContributor;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.GlobalAuthenticationPolicyPort;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.AccountOwnerPort;
import com.lumira.platform.identity.api.AccountSecurityPort;
import com.lumira.platform.identity.api.AuthenticationSession;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.identity.api.LoginResponse;
import com.lumira.platform.identity.api.ReauthenticationPort;
import com.lumira.platform.identity.api.SecondFactorSession;
import com.lumira.platform.identity.api.WechatConfigurationPort;
import com.lumira.platform.identity.api.WechatRegistrationBindingPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Identity-owned WeChat attempts and account bindings. Provider I/O always runs outside DB
 * transactions.
 */
@Service
public class WechatIdentityService
        implements AuthenticationCapabilityContributor, WechatRegistrationBindingPort {
    private static final int ATTEMPT_LIFETIME_SECONDS = 300;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ATTEMPT_COLUMNS =
            """
            id,purpose,attempt_state AS state,session_digest,settings_version,app_id,contact_proof_id,
            user_id,base_binding_version,replace_binding,authentication_revision,
            primary_credential_revision,factor_credential_revision,open_id_digest,nickname,
            registration_proof_id,login_response::text AS login_json,failure_code,expires_at
            """;

    public record StartInput(String purpose, String registrationProofId, String captchaProofId) {}

    public record StartView(
            UUID attemptId, String purpose, String authorizationUrl, Instant expiresAt) {}

    public record Result(
            String status,
            String attemptId,
            String purpose,
            Instant expiresAt,
            LoginResponse login,
            String wechatRegistrationProofId,
            IdentityView identity,
            String failureCode) {
        public static Result none() {
            return new Result("NONE", null, null, null, null, null, null, null);
        }
    }

    public record IdentityView(String nickname) {}

    public record AccountView(long version, boolean bound, boolean usable, IdentityView identity) {}

    public record BindingStart(long version, boolean replace, String reauthenticationTicketId) {}

    public record BindingConfirmation(String attemptId, long version, Boolean confirm) {}

    public record Unbind(long version, String reauthenticationTicketId) {}

    private record Attempt(
            UUID id,
            String purpose,
            String state,
            String sessionDigest,
            long settingsVersion,
            String appId,
            UUID contactProofId,
            UUID userId,
            Long baseBindingVersion,
            boolean replaceBinding,
            UUID authenticationRevision,
            UUID primaryCredentialRevision,
            UUID factorCredentialRevision,
            String openIdDigest,
            String nickname,
            UUID registrationProofId,
            String loginJson,
            String failureCode,
            OffsetDateTime expiresAt) {}

    private record CallbackClaim(
            UUID attemptId,
            String purpose,
            String sessionDigest,
            long settingsVersion,
            String appId,
            UUID contactProofId,
            UUID userId,
            Long baseBindingVersion,
            boolean replaceBinding,
            UUID authenticationRevision,
            UUID primaryCredentialRevision,
            UUID factorCredentialRevision) {}

    private record BoundAccount(UUID userId, long version) {}

    private record LoginProof(
            String username,
            UUID primaryCredentialRevision,
            long bindingRevision,
            OffsetDateTime expiresAt) {}

    private record BindingRow(long version, String appId, String digest, String nickname) {
        boolean bound() {
            return appId != null && digest != null;
        }
    }

    private record BindingMutation(
            AccountView account,
            boolean preserveCurrentSession,
            UUID userId,
            EffectiveAuthenticationPolicyPort.EffectivePolicy effective) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final PublicAuthCrypto crypto;
    private final PublicAuthChallengeStore publicChallenges;
    private final CaptchaProofAccess captcha;
    private final WechatConfigurationPort settings;
    private final WechatProviderClient provider;
    private final GlobalAuthenticationPolicyPort globalPolicy;
    private final AccountOwnerPort owners;
    private final AuthenticationPolicyWriteLock policyLock;
    private final EffectiveAuthenticationPolicyPort policies;
    private final PrimaryAuthenticationPort primaryAuthentication;
    private final UserDetailsService users;
    private final ReauthenticationPort reauthentication;
    private final AccountSecurityPort accountSecurity;
    private final WechatRateLimiter limiter;

    @Autowired
    public WechatIdentityService(
            JdbcClient jdbc,
            TransactionTemplate tx,
            ObjectMapper mapper,
            PublicAuthCrypto crypto,
            PublicAuthChallengeStore publicChallenges,
            CaptchaProofAccess captcha,
            WechatConfigurationPort settings,
            WechatProviderClient provider,
            GlobalAuthenticationPolicyPort globalPolicy,
            AccountOwnerPort owners,
            AuthenticationPolicyWriteLock policyLock,
            EffectiveAuthenticationPolicyPort policies,
            PrimaryAuthenticationPort primaryAuthentication,
            UserDetailsService users,
            ReauthenticationPort reauthentication,
            AccountSecurityPort accountSecurity,
            WechatRateLimiter limiter) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.crypto = crypto;
        this.publicChallenges = publicChallenges;
        this.captcha = captcha;
        this.settings = settings;
        this.provider = provider;
        this.globalPolicy = globalPolicy;
        this.owners = owners;
        this.policyLock = policyLock;
        this.policies = policies;
        this.primaryAuthentication = primaryAuthentication;
        this.users = users;
        this.reauthentication = reauthentication;
        this.accountSecurity = accountSecurity;
        this.limiter = limiter;
    }

    @Override
    public String id() {
        return "wechat";
    }

    @Override
    public Contribution authenticationCapabilities() {
        boolean ready =
                settings.ready()
                        && globalPolicy.current().enabledMethods().contains(PrimaryMethod.WECHAT);
        Set<PrimaryMethod> methods = ready ? Set.of(PrimaryMethod.WECHAT) : Set.of();
        return new Contribution(methods, false, ready, false, false, null, null);
    }

    @Override
    public AccountMethods accountMethods(UUID userId) {
        var config = settings.snapshot();
        var binding = readBinding(userId, false);
        boolean usable =
                config.ready()
                        && binding.bound()
                        && config.appId().equals(binding.appId())
                        && activeAccount(userId);
        return new AccountMethods(
                usable ? Set.of(PrimaryMethod.WECHAT) : Set.of(),
                "wechat-binding:" + binding.version());
    }

    public StartView startPublic(StartInput input, HttpServletRequest request) {
        if (input == null
                || !("LOGIN".equals(input.purpose()) || "REGISTRATION".equals(input.purpose())))
            throw badRequest("invalidWechatAttempt");
        String sessionId = sessionId(request, true);
        String sessionDigest = crypto.sessionDigest(sessionId);
        if (!limiter.allow(sessionDigest, request))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "wechatRateLimited");
        return tx.execute(
                status -> {
                    requirePublicProvider();
                    UUID contactProof = null;
                    if ("REGISTRATION".equals(input.purpose())) {
                        contactProof =
                                parseUuid(input.registrationProofId(), "registrationProofInvalid");
                        var verified =
                                publicChallenges
                                        .verifiedByProof(
                                                contactProof,
                                                sessionDigest,
                                                PublicAuthPurpose.REGISTRATION,
                                                false)
                                        .orElseThrow(() -> badRequest("registrationProofInvalid"));
                        if (!verified.proofActive(OffsetDateTime.now()))
                            throw badRequest("registrationProofExpired");
                    } else if (input.registrationProofId() != null) {
                        throw badRequest("invalidWechatAttempt");
                    }
                    var gate =
                            captcha.begin(
                                    CaptchaProofPort.Action.WECHAT_LOGIN, null, null, request);
                    captcha.consume(gate, input.captchaProofId(), request, null);
                    var config = settings.snapshot();
                    UUID id = UUID.randomUUID();
                    String rawState = randomState();
                    return insertAttempt(
                            id,
                            rawState,
                            sessionDigest,
                            input.purpose(),
                            config.version(),
                            config.appId(),
                            contactProof,
                            null,
                            null,
                            false,
                            null,
                            null,
                            null,
                            config.publicOrigin());
                });
    }

    /**
     * Handles WeChat's browser callback; no transaction is held while the provider is contacted.
     */
    public void callback(
            String state,
            String code,
            String providerError,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}")) return;
        String sessionId = sessionId(request, false);
        if (sessionId == null) return;
        String sessionDigest = crypto.sessionDigest(sessionId);
        CallbackClaim claim = tx.execute(status -> claimCallback(state, sessionDigest));
        if (claim == null) return;
        if (providerError != null && !providerError.isBlank()) {
            finishCallback(
                    claim,
                    "authdeny".equalsIgnoreCase(providerError) ? "CANCELLED" : "FAILED",
                    null,
                    null);
            return;
        }
        if (code == null || !code.matches("[\\x21-\\x7e]{1,512}")) {
            finishCallback(claim, "FAILED", null, "wechatProviderUnavailable");
            return;
        }

        WechatProviderClient.Identity identity;
        try (WechatConfigurationPort.ProviderConfiguration config =
                settings.providerConfiguration()) {
            if (config.version() != claim.settingsVersion()
                    || !config.appId().equals(claim.appId())) {
                finishCallback(claim, "FAILED", null, "wechatUnavailable");
                return;
            }
            identity =
                    provider.exchange(
                            config.appId(), config.secret(), code, config.timeoutSeconds());
        } catch (ResponseStatusException unavailable) {
            finishCallback(claim, "FAILED", null, "wechatProviderUnavailable");
            return;
        } catch (RuntimeException failure) {
            finishCallback(claim, "FAILED", null, "wechatProviderUnavailable");
            return;
        }
        String identityDigest = identityDigest(claim.appId(), identity.openId());
        var currentSettings = settings.snapshot();
        if (!currentSettings.ready()
                || currentSettings.version() != claim.settingsVersion()
                || !currentSettings.appId().equals(claim.appId())) {
            finishCallback(claim, "FAILED", null, "wechatUnavailable");
            return;
        }

        switch (claim.purpose()) {
            case "LOGIN" ->
                    finishLoginCallback(
                            claim, identityDigest, identity.nickname(), request, response);
            case "BIND", "REGISTRATION" ->
                    finishIdentityCallback(claim, identityDigest, identity.nickname());
            default -> finishCallback(claim, "FAILED", null, "wechatUnavailable");
        }
    }

    public Result result(HttpServletRequest request) {
        String sessionId = sessionId(request, false);
        if (sessionId == null) return Result.none();
        String digest = crypto.sessionDigest(sessionId);
        Attempt attempt =
                jdbc.sql(
                                "SELECT "
                                        + ATTEMPT_COLUMNS
                                        + " FROM platform_wechat_attempt WHERE session_digest=:session"
                                        + " ORDER BY created_at DESC,id DESC LIMIT 1")
                        .param("session", digest)
                        .query(WechatIdentityService::mapAttempt)
                        .optional()
                        .orElse(null);
        if (attempt == null) return Result.none();
        String state = publicState(attempt);
        if (state.equals("EXPIRED"))
            return new Result(
                    state,
                    attempt.id().toString(),
                    attempt.purpose(),
                    attempt.expiresAt().toInstant(),
                    null,
                    null,
                    null,
                    null);
        LoginResponse login = null;
        if ("READY".equals(state) && attempt.loginJson() != null) {
            try {
                login = mapper.readValue(attempt.loginJson(), LoginResponse.class);
            } catch (RuntimeException ignored) {
                state = "FAILED";
            }
        }
        String registrationProof =
                "READY".equals(state) && attempt.registrationProofId() != null
                        ? attempt.registrationProofId().toString()
                        : null;
        IdentityView identity =
                ("READY".equals(state) || "UNBOUND".equals(state)) && attempt.openIdDigest() != null
                        ? new IdentityView(attempt.nickname())
                        : null;
        return new Result(
                state,
                attempt.id().toString(),
                attempt.purpose(),
                attempt.expiresAt().toInstant(),
                login,
                registrationProof,
                identity,
                "FAILED".equals(state) ? attempt.failureCode() : null);
    }

    public AccountView account(String actor, HttpServletRequest request) {
        return tx.execute(
                status -> {
                    UUID userId = owners.id(actor, false);
                    return accountView(userId);
                });
    }

    public StartView startBinding(String actor, BindingStart input, HttpServletRequest request) {
        if (input == null || input.version() < 0) throw badRequest("invalidWechatBinding");
        UUID ticket = parseUuid(input.reauthenticationTicketId(), "reauthenticationRequired");
        String session = sessionId(request, false);
        requireCompleteAuthentication(request);
        String sessionDigest = crypto.sessionDigest(session);
        return tx.execute(
                status -> {
                    policyLock.lockForAuthentication();
                    UUID userId = owners.id(actor, true);
                    requirePublicProvider();
                    BindingRow binding = readBinding(userId, true);
                    if (binding.version() != input.version()) throw conflict("staleRecord");
                    if (binding.bound() && !input.replace()) throw conflict("wechatAppInUse");
                    reauthentication.consume(
                            ticket, userId, ReauthenticationPort.Action.WECHAT_BIND, session);
                    var effective = policies.resolve(userId);
                    var config = settings.snapshot();
                    UUID id = UUID.randomUUID();
                    String rawState = randomState();
                    return insertAttempt(
                            id,
                            rawState,
                            sessionDigest,
                            "BIND",
                            config.version(),
                            config.appId(),
                            null,
                            userId,
                            binding.version(),
                            input.replace(),
                            effective.revision(),
                            effective.primaryCredentialRevision(),
                            effective.factorCredentialRevision(),
                            config.publicOrigin());
                });
    }

    public AccountView confirmBinding(
            String actor, BindingConfirmation input, HttpServletRequest request) {
        if (input == null || !Boolean.TRUE.equals(input.confirm()) || input.version() < 0)
            throw badRequest("wechatExplicitConfirmationRequired");
        UUID attemptId = parseUuid(input.attemptId(), "wechatUnavailable");
        String session = sessionId(request, false);
        requireCompleteAuthentication(request);
        String sessionDigest = crypto.sessionDigest(session);
        BindingMutation mutation =
                tx.execute(
                        status -> {
                            policyLock.lockForAuthentication();
                            UUID userId = owners.id(actor, true);
                            var attempt =
                                    findAttempt(attemptId, sessionDigest, "BIND", true)
                                            .orElseThrow(() -> conflict("wechatUnavailable"));
                            validateReadyBindAttempt(attempt, userId, input.version());
                            BindingRow current = readBinding(userId, true);
                            if (current.version() != attempt.baseBindingVersion())
                                throw conflict("staleRecord");
                            var currentPolicy = policies.resolve(userId);
                            if (!sameAuthenticationRevision(attempt, currentPolicy))
                                throw conflict("authorizationContextChanged");
                            var config = settings.snapshot();
                            if (!config.ready()
                                    || config.version() != attempt.settingsVersion()
                                    || !config.appId().equals(attempt.appId()))
                                throw conflict("wechatUnavailable");
                            lockIdentity(attempt.appId(), attempt.openIdDigest());
                            UUID anotherOwner =
                                    findIdentityOwner(attempt.appId(), attempt.openIdDigest());
                            if (anotherOwner != null && !anotherOwner.equals(userId))
                                throw conflict("wechatAlreadyUsed");
                            long nextVersion =
                                    writeBinding(
                                            userId,
                                            attempt.baseBindingVersion(),
                                            attempt.appId(),
                                            attempt.openIdDigest(),
                                            attempt.nickname());
                            accountSecurity.bumpPrimaryCredentialRevision(userId);
                            var nextPolicy = policies.resolve(userId);
                            boolean preserveSession = retainCurrentSession(request, nextPolicy);
                            int consumed =
                                    jdbc.sql(
                                                    "UPDATE platform_wechat_attempt SET attempt_state='CONSUMED',completed_at=now() WHERE id=:id AND attempt_state='READY'")
                                            .param("id", attemptId)
                                            .update();
                            if (consumed != 1) throw conflict("wechatUnavailable");
                            accountSecurity.revokeSessions(
                                    userId, preserveSession ? session : null);
                            var view =
                                    view(
                                            userId,
                                            nextVersion,
                                            attempt.appId(),
                                            attempt.openIdDigest(),
                                            attempt.nickname(),
                                            nextPolicy);
                            return new BindingMutation(view, preserveSession, userId, nextPolicy);
                        });
        refreshOrInvalidateCurrentSession(request, mutation);
        return mutation.account();
    }

    public void unbind(String actor, Unbind input, HttpServletRequest request) {
        if (input == null || input.version() < 0) throw badRequest("invalidWechatBinding");
        UUID ticket = parseUuid(input.reauthenticationTicketId(), "reauthenticationRequired");
        String session = sessionId(request, false);
        requireCompleteAuthentication(request);
        String sessionDigest = crypto.sessionDigest(session);
        BindingMutation mutation =
                tx.execute(
                        status -> {
                            policyLock.lockForAuthentication();
                            UUID userId = owners.id(actor, true);
                            BindingRow current = readBinding(userId, true);
                            if (current.version() != input.version()) throw conflict("staleRecord");
                            if (!current.bound()) throw conflict("wechatUnavailable");
                            var effective = policies.resolve(userId);
                            boolean hasAlternative =
                                    effective.usableMethods().stream()
                                            .anyMatch(method -> method != PrimaryMethod.WECHAT);
                            if (!hasAlternative) throw conflict("wechatRequiredUsableMethod");
                            reauthentication.consume(
                                    ticket,
                                    userId,
                                    ReauthenticationPort.Action.WECHAT_UNBIND,
                                    session);
                            AccountView account = unbindLocked(userId, current);
                            var nextPolicy = policies.resolve(userId);
                            boolean preserveSession = retainCurrentSession(request, nextPolicy);
                            accountSecurity.revokeSessions(
                                    userId, preserveSession ? session : null);
                            return new BindingMutation(
                                    account, preserveSession, userId, nextPolicy);
                        });
        refreshOrInvalidateCurrentSession(request, mutation);
    }

    @Override
    public WechatRegistrationBindingPort.BindingResult consumeForRegistration(
            String wechatRegistrationProofId,
            String registrationContactProofId,
            UUID newAccountId,
            String sessionDigest) {
        if (wechatRegistrationProofId == null || wechatRegistrationProofId.isBlank())
            return WechatRegistrationBindingPort.BindingResult.notRequested();
        UUID proof;
        UUID contactProof;
        if (newAccountId == null || sessionDigest == null || !sessionDigest.matches("[a-f0-9]{64}"))
            return WechatRegistrationBindingPort.BindingResult.failed("wechatRegistrationFailed");
        try {
            proof = UUID.fromString(wechatRegistrationProofId);
            contactProof = UUID.fromString(registrationContactProofId);
        } catch (RuntimeException invalid) {
            return WechatRegistrationBindingPort.BindingResult.failed("wechatRegistrationFailed");
        }
        return tx.execute(
                status ->
                        consumeRegistrationProof(proof, contactProof, newAccountId, sessionDigest));
    }

    private WechatRegistrationBindingPort.BindingResult consumeRegistrationProof(
            UUID registrationProof, UUID contactProof, UUID newAccountId, String sessionDigest) {
        Attempt attempt =
                jdbc.sql(
                                "SELECT "
                                        + ATTEMPT_COLUMNS
                                        + " FROM platform_wechat_attempt WHERE registration_proof_id=:proof"
                                        + " AND purpose='REGISTRATION' AND session_digest=:session FOR UPDATE")
                        .param("proof", registrationProof)
                        .param("session", sessionDigest)
                        .query(WechatIdentityService::mapAttempt)
                        .optional()
                        .orElse(null);
        if (attempt == null
                || !"READY".equals(attempt.state())
                || !attempt.expiresAt().isAfter(OffsetDateTime.now())
                || attempt.contactProofId() == null
                || !attempt.contactProofId().equals(contactProof)
                || attempt.openIdDigest() == null)
            return WechatRegistrationBindingPort.BindingResult.failed("wechatRegistrationFailed");
        var contact =
                publicChallenges
                        .verifiedByProof(
                                contactProof, sessionDigest, PublicAuthPurpose.REGISTRATION, true)
                        .orElse(null);
        if (contact == null || !contact.proofActive(OffsetDateTime.now()))
            return consumeRegistrationFailure(attempt, "wechatRegistrationFailed");
        var config = settings.snapshot();
        if (!config.ready()
                || config.version() != attempt.settingsVersion()
                || !config.appId().equals(attempt.appId())
                || !globalPolicy.current().enabledMethods().contains(PrimaryMethod.WECHAT))
            return consumeRegistrationFailure(attempt, "wechatRegistrationUnavailable");
        lockIdentity(attempt.appId(), attempt.openIdDigest());
        UUID owner = findIdentityOwner(attempt.appId(), attempt.openIdDigest());
        if (owner != null) return consumeRegistrationFailure(attempt, "wechatAlreadyUsed");
        boolean inserted =
                jdbc.sql(
                                """
                        INSERT INTO platform_wechat_binding(user_id,version,app_id,open_id_digest,nickname,updated_at)
                        VALUES(:user,1,:app,:digest,:nickname,now())
                        ON CONFLICT DO NOTHING RETURNING user_id
                        """)
                        .param("user", newAccountId)
                        .param("app", attempt.appId())
                        .param("digest", attempt.openIdDigest())
                        .param("nickname", attempt.nickname())
                        .query(UUID.class)
                        .optional()
                        .isPresent();
        if (!inserted) return consumeRegistrationFailure(attempt, "wechatAlreadyUsed");
        jdbc.sql(
                        "UPDATE platform_wechat_attempt SET attempt_state='CONSUMED',registration_proof_id=NULL,completed_at=now() WHERE id=:id AND attempt_state='READY'")
                .param("id", attempt.id())
                .update();
        return WechatRegistrationBindingPort.BindingResult.bound();
    }

    private WechatRegistrationBindingPort.BindingResult consumeRegistrationFailure(
            Attempt attempt, String reason) {
        String safeReason =
                Set.of("wechatAlreadyUsed", "wechatRegistrationUnavailable").contains(reason)
                        ? reason
                        : "wechatRegistrationFailed";
        jdbc.sql(
                        "UPDATE platform_wechat_attempt SET attempt_state='FAILED',registration_proof_id=NULL,failure_code=:failure,completed_at=now() WHERE id=:id AND attempt_state='READY'")
                .param("id", attempt.id())
                .param("failure", safeReason)
                .update();
        return WechatRegistrationBindingPort.BindingResult.failed(safeReason);
    }

    private AccountView unbindLocked(UUID userId, BindingRow current) {
        int updated =
                jdbc.sql(
                                "UPDATE platform_wechat_binding SET version=version+1,app_id=NULL,open_id_digest=NULL,nickname=NULL,updated_at=now() WHERE user_id=:user AND version=:version")
                        .param("user", userId)
                        .param("version", current.version())
                        .update();
        if (updated != 1) throw conflict("staleRecord");
        accountSecurity.bumpPrimaryCredentialRevision(userId);
        var nextPolicy = policies.resolve(userId);
        return view(userId, current.version() + 1, null, null, null, nextPolicy);
    }

    private StartView insertAttempt(
            UUID id,
            String rawState,
            String sessionDigest,
            String purpose,
            long settingsVersion,
            String appId,
            UUID contactProofId,
            UUID userId,
            Long baseBindingVersion,
            boolean replaceBinding,
            UUID authenticationRevision,
            UUID primaryCredentialRevision,
            UUID factorCredentialRevision,
            String publicOrigin) {
        boolean completing =
                jdbc.sql(
                                "SELECT EXISTS(SELECT 1 FROM platform_wechat_attempt WHERE session_digest=:session AND purpose=:purpose AND attempt_state='AUTHENTICATING' AND expires_at>CURRENT_TIMESTAMP)")
                        .param("session", sessionDigest)
                        .param("purpose", purpose)
                        .query(Boolean.class)
                        .single();
        if (completing) throw conflict("wechatUnavailable");
        jdbc.sql(
                        "DELETE FROM platform_wechat_attempt WHERE created_at<CURRENT_TIMESTAMP-INTERVAL '1 day'")
                .update();
        jdbc.sql(
                        "UPDATE platform_wechat_attempt SET attempt_state='SUPERSEDED',registration_proof_id=NULL,login_response=NULL,completed_at=now() WHERE session_digest=:session AND purpose=:purpose AND attempt_state IN ('PENDING','CLAIMED','READY')")
                .param("session", sessionDigest)
                .param("purpose", purpose)
                .update();
        Instant expires = Instant.now().plusSeconds(ATTEMPT_LIFETIME_SECONDS);
        jdbc.sql(
                        """
                        INSERT INTO platform_wechat_attempt
                            (id,purpose,session_digest,state_digest,settings_version,app_id,contact_proof_id,
                             user_id,base_binding_version,replace_binding,authentication_revision,
                             primary_credential_revision,factor_credential_revision,expires_at)
                        VALUES(:id,:purpose,:session,:state,:settingsVersion,:appId,:contactProof,
                               :user,:bindingVersion,:replace,:authRevision,:primaryRevision,:factorRevision,
                               :expires)
                        """)
                .param("id", id)
                .param("purpose", purpose)
                .param("session", sessionDigest)
                .param("state", sha256(rawState))
                .param("settingsVersion", settingsVersion)
                .param("appId", appId)
                .param("contactProof", contactProofId)
                .param("user", userId)
                .param("bindingVersion", baseBindingVersion)
                .param("replace", replaceBinding)
                .param("authRevision", authenticationRevision)
                .param("primaryRevision", primaryCredentialRevision)
                .param("factorRevision", factorCredentialRevision)
                .param("expires", java.sql.Timestamp.from(expires))
                .update();
        return new StartView(id, purpose, authorizationUrl(appId, publicOrigin, rawState), expires);
    }

    private CallbackClaim claimCallback(String rawState, String sessionDigest) {
        Attempt attempt =
                jdbc.sql(
                                "SELECT "
                                        + ATTEMPT_COLUMNS
                                        + " FROM platform_wechat_attempt WHERE state_digest=:state AND session_digest=:session FOR UPDATE")
                        .param("state", sha256(rawState))
                        .param("session", sessionDigest)
                        .query(WechatIdentityService::mapAttempt)
                        .optional()
                        .orElse(null);
        if (attempt == null || !"PENDING".equals(attempt.state())) return null;
        if (!attempt.expiresAt().isAfter(OffsetDateTime.now())) {
            jdbc.sql(
                            "UPDATE platform_wechat_attempt SET attempt_state='EXPIRED',completed_at=now() WHERE id=:id")
                    .param("id", attempt.id())
                    .update();
            return null;
        }
        var config = settings.snapshot();
        if (!config.ready()
                || config.version() != attempt.settingsVersion()
                || !config.appId().equals(attempt.appId())) {
            jdbc.sql(
                            "UPDATE platform_wechat_attempt SET attempt_state='FAILED',failure_code='wechatUnavailable',completed_at=now() WHERE id=:id")
                    .param("id", attempt.id())
                    .update();
            return null;
        }
        int claimed =
                jdbc.sql(
                                "UPDATE platform_wechat_attempt SET attempt_state='CLAIMED',claimed_at=now() WHERE id=:id AND attempt_state='PENDING'")
                        .param("id", attempt.id())
                        .update();
        if (claimed != 1) return null;
        return new CallbackClaim(
                attempt.id(),
                attempt.purpose(),
                attempt.sessionDigest(),
                attempt.settingsVersion(),
                attempt.appId(),
                attempt.contactProofId(),
                attempt.userId(),
                attempt.baseBindingVersion(),
                attempt.replaceBinding(),
                attempt.authenticationRevision(),
                attempt.primaryCredentialRevision(),
                attempt.factorCredentialRevision());
    }

    private void finishCallback(
            CallbackClaim claim, String state, String openIdDigest, String failureCode) {
        if (!Set.of("CANCELLED", "FAILED").contains(state))
            throw new IllegalArgumentException("invalidWechatCallbackState");
        tx.executeWithoutResult(
                status -> {
                    Attempt current =
                            findAttempt(
                                            claim.attemptId(),
                                            claim.sessionDigest(),
                                            claim.purpose(),
                                            true)
                                    .orElse(null);
                    if (current == null || !"CLAIMED".equals(current.state())) return;
                    jdbc.sql(
                                    "UPDATE platform_wechat_attempt SET attempt_state=:state,open_id_digest=:digest,failure_code=:failure,completed_at=now() WHERE id=:id AND attempt_state='CLAIMED'")
                            .param("state", state)
                            .param("digest", openIdDigest)
                            .param("failure", failureCode == null ? null : safeFailure(failureCode))
                            .param("id", claim.attemptId())
                            .update();
                });
    }

    private void finishIdentityCallback(CallbackClaim claim, String digest, String nickname) {
        tx.executeWithoutResult(
                status -> {
                    Attempt current =
                            findAttempt(
                                            claim.attemptId(),
                                            claim.sessionDigest(),
                                            claim.purpose(),
                                            true)
                                    .orElse(null);
                    if (current == null || !"CLAIMED".equals(current.state())) return;
                    var config = settings.snapshot();
                    if (!config.ready()
                            || config.version() != claim.settingsVersion()
                            || !config.appId().equals(claim.appId())) {
                        setAttemptFailure(claim.attemptId(), "wechatUnavailable");
                        return;
                    }
                    if ("REGISTRATION".equals(claim.purpose())) {
                        var contact =
                                claim.contactProofId() == null
                                        ? null
                                        : publicChallenges
                                                .verifiedByProof(
                                                        claim.contactProofId(),
                                                        claim.sessionDigest(),
                                                        PublicAuthPurpose.REGISTRATION,
                                                        false)
                                                .orElse(null);
                        if (contact == null || !contact.proofActive(OffsetDateTime.now())) {
                            setAttemptFailure(claim.attemptId(), "wechatRegistrationFailed");
                            return;
                        }
                    } else if (!"BIND".equals(claim.purpose())
                            || claim.userId() == null
                            || !activeAccount(claim.userId())) {
                        setAttemptFailure(claim.attemptId(), "wechatUnavailable");
                        return;
                    }
                    UUID proof = "REGISTRATION".equals(claim.purpose()) ? UUID.randomUUID() : null;
                    jdbc.sql(
                                    "UPDATE platform_wechat_attempt SET attempt_state='READY',open_id_digest=:digest,nickname=:nickname,registration_proof_id=:proof,completed_at=now() WHERE id=:id AND attempt_state='CLAIMED'")
                            .param("digest", digest)
                            .param("nickname", nickname)
                            .param("proof", proof)
                            .param("id", claim.attemptId())
                            .update();
                });
    }

    private void finishLoginCallback(
            CallbackClaim claim,
            String digest,
            String nickname,
            HttpServletRequest request,
            HttpServletResponse response) {
        boolean primaryCompleted = false;
        try {
            LoginProof proof =
                    tx.execute(
                            status -> {
                                policyLock.lockForAuthentication();
                                Attempt current =
                                        findAttempt(
                                                        claim.attemptId(),
                                                        claim.sessionDigest(),
                                                        "LOGIN",
                                                        true)
                                                .orElseThrow(
                                                        () -> unavailable("wechatUnavailable"));
                                var config = settings.snapshot();
                                if (!"CLAIMED".equals(current.state())
                                        || !config.ready()
                                        || config.version() != claim.settingsVersion()
                                        || !config.appId().equals(claim.appId()))
                                    throw unavailable("wechatUnavailable");

                                UUID ownerId = findIdentityOwner(claim.appId(), digest);
                                if (ownerId == null) {
                                    jdbc.sql(
                                                    "UPDATE platform_wechat_attempt SET attempt_state='UNBOUND',open_id_digest=:digest,nickname=:nickname,completed_at=now() WHERE id=:id AND attempt_state='CLAIMED'")
                                            .param("digest", digest)
                                            .param("nickname", nickname)
                                            .param("id", claim.attemptId())
                                            .update();
                                    return null;
                                }
                                var user =
                                        jdbc.sql(
                                                        "SELECT id,username,primary_credential_revision FROM platform_user WHERE id=:id AND enabled AND deleted_at IS NULL FOR UPDATE")
                                                .param("id", ownerId)
                                                .query(
                                                        (rs, row) ->
                                                                new UserPrimary(
                                                                        rs.getObject(
                                                                                "id", UUID.class),
                                                                        rs.getString("username"),
                                                                        rs.getObject(
                                                                                "primary_credential_revision",
                                                                                UUID.class)))
                                                .optional()
                                                .orElseThrow(
                                                        () -> unavailable("wechatUnavailable"));
                                BindingRow binding = readBinding(user.id(), true);
                                if (!binding.bound()
                                        || !claim.appId().equals(binding.appId())
                                        || !digest.equals(binding.digest()))
                                    throw unavailable("wechatUnavailable");
                                var effective = policies.resolve(user.id());
                                if (!effective.allowedMethods().contains(PrimaryMethod.WECHAT)
                                        || !effective.usableMethods().contains(PrimaryMethod.WECHAT)
                                        || !effective
                                                .primaryCredentialRevision()
                                                .equals(user.primaryCredentialRevision()))
                                    throw unavailable("wechatUnavailable");
                                int staged =
                                        jdbc.sql(
                                                        "UPDATE platform_wechat_attempt SET attempt_state='AUTHENTICATING',open_id_digest=:digest,nickname=:nickname WHERE id=:id AND attempt_state='CLAIMED'")
                                                .param("digest", digest)
                                                .param("nickname", nickname)
                                                .param("id", claim.attemptId())
                                                .update();
                                if (staged != 1) throw unavailable("wechatUnavailable");
                                return new LoginProof(
                                        user.username(),
                                        user.primaryCredentialRevision(),
                                        binding.version(),
                                        current.expiresAt());
                            });
            if (proof == null) return;
            if (!proof.expiresAt().isAfter(OffsetDateTime.now())) {
                markLoginFailure(claim, claim.sessionDigest());
                return;
            }
            var details = users.loadUserByUsername(proof.username());
            var auth =
                    UsernamePasswordAuthenticationToken.authenticated(
                            details, null, details.getAuthorities());
            auth.setDetails(
                    Map.of(
                            "primaryMethod", PrimaryMethod.WECHAT.name(),
                            "bindingRevision", proof.bindingRevision(),
                            "primaryCredentialRevision",
                                    proof.primaryCredentialRevision().toString()));
            LoginResponse login =
                    primaryAuthentication.complete(
                            auth,
                            PrimaryMethod.WECHAT,
                            proof.primaryCredentialRevision().toString(),
                            request,
                            response);
            primaryCompleted = true;
            HttpSession currentSession = request.getSession(false);
            if (currentSession == null) throw unavailable("wechatLoginFailed");
            String rotatedSessionDigest = crypto.sessionDigest(currentSession.getId());
            int updated =
                    tx.execute(
                            status ->
                                    jdbc.sql(
                                                    "UPDATE platform_wechat_attempt SET attempt_state='READY',session_digest=:session,login_response=CAST(:login AS jsonb),completed_at=now() WHERE id=:id AND attempt_state='AUTHENTICATING' AND expires_at>CURRENT_TIMESTAMP")
                                            .param("session", rotatedSessionDigest)
                                            .param("login", mapper.writeValueAsString(login))
                                            .param("id", claim.attemptId())
                                            .update());
            if (updated != 1) throw unavailable("wechatLoginFailed");
        } catch (RuntimeException ignored) {
            try {
                if (primaryCompleted) clearCurrentAuthenticationSession(request);
                HttpSession current = request.getSession(false);
                if (current == null) current = request.getSession(true);
                markLoginFailure(claim, crypto.sessionDigest(current.getId()));
            } catch (RuntimeException failurePersistence) {
                // Do not turn a browser callback into an error page or log sensitive provider
                // parameters.
            }
        }
    }

    static void clearCurrentAuthenticationSession(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession current = request.getSession(false);
        if (current == null) return;
        try {
            current.removeAttribute(
                    org.springframework.security.web.context.HttpSessionSecurityContextRepository
                            .SPRING_SECURITY_CONTEXT_KEY);
            current.removeAttribute(AuthenticationSession.PRIMARY_METHOD);
            current.removeAttribute(AuthenticationSession.VERIFIED_FACTORS);
            current.removeAttribute(AuthenticationSession.AUTHENTICATED_AT);
            current.removeAttribute(AuthenticationSession.AUTHENTICATION_REVISION);
            current.removeAttribute(AuthenticationSession.PRIMARY_CREDENTIAL_REVISION);
            current.removeAttribute(AuthenticationSession.FACTOR_CREDENTIAL_REVISION);
            current.removeAttribute(SecondFactorSession.REQUIREMENT_VERSION_ATTRIBUTE);
            current.removeAttribute("lumira.login.challenge");
            current.invalidate();
        } catch (IllegalStateException alreadyInvalidated) {
            SecurityContextHolder.clearContext();
        }
    }

    private void markLoginFailure(CallbackClaim claim, String sessionDigest) {
        tx.executeWithoutResult(
                status ->
                        jdbc.sql(
                                        "UPDATE platform_wechat_attempt SET attempt_state='FAILED',session_digest=:session,failure_code='wechatLoginFailed',login_response=NULL,completed_at=now() WHERE id=:id AND attempt_state IN ('CLAIMED','AUTHENTICATING')")
                                .param("session", sessionDigest)
                                .param("id", claim.attemptId())
                                .update());
    }

    private void setAttemptFailure(UUID attemptId, String failure) {
        jdbc.sql(
                        "UPDATE platform_wechat_attempt SET attempt_state='FAILED',failure_code=:failure,registration_proof_id=NULL,login_response=NULL,completed_at=now() WHERE id=:id AND attempt_state IN ('PENDING','CLAIMED','READY')")
                .param("failure", safeFailure(failure))
                .param("id", attemptId)
                .update();
    }

    private AccountView accountView(UUID userId) {
        BindingRow binding = readBinding(userId, false);
        return view(
                userId,
                binding.version(),
                binding.appId(),
                binding.digest(),
                binding.nickname(),
                policies.resolve(userId));
    }

    private AccountView view(
            UUID userId,
            long version,
            String appId,
            String digest,
            String nickname,
            EffectiveAuthenticationPolicyPort.EffectivePolicy effective) {
        boolean bound = appId != null && digest != null;
        var config = settings.snapshot();
        boolean usable =
                bound
                        && config.ready()
                        && config.appId().equals(appId)
                        && effective.allowedMethods().contains(PrimaryMethod.WECHAT)
                        && effective.usableMethods().contains(PrimaryMethod.WECHAT)
                        && activeAccount(userId);
        return new AccountView(version, bound, usable, bound ? new IdentityView(nickname) : null);
    }

    private BindingRow readBinding(UUID userId, boolean lock) {
        return jdbc.sql(
                        "SELECT version,app_id,open_id_digest,nickname FROM platform_wechat_binding WHERE user_id=:user"
                                + (lock ? " FOR UPDATE" : ""))
                .param("user", userId)
                .query(
                        (rs, row) ->
                                new BindingRow(
                                        rs.getLong("version"),
                                        rs.getString("app_id"),
                                        rs.getString("open_id_digest"),
                                        rs.getString("nickname")))
                .optional()
                .orElseGet(() -> new BindingRow(0, null, null, null));
    }

    private long writeBinding(
            UUID userId, long expectedVersion, String appId, String digest, String nickname) {
        if (expectedVersion == 0) {
            return jdbc.sql(
                            """
                            INSERT INTO platform_wechat_binding(user_id,version,app_id,open_id_digest,nickname,updated_at)
                            VALUES(:user,1,:app,:digest,:nickname,now())
                            ON CONFLICT(user_id) DO NOTHING
                            RETURNING version
                            """)
                    .param("user", userId)
                    .param("app", appId)
                    .param("digest", digest)
                    .param("nickname", nickname)
                    .query(Long.class)
                    .optional()
                    .orElseThrow(() -> conflict("staleRecord"));
        }
        return jdbc.sql(
                        """
                        UPDATE platform_wechat_binding
                           SET version=version+1,app_id=:app,open_id_digest=:digest,nickname=:nickname,updated_at=now()
                         WHERE user_id=:user AND version=:version
                        RETURNING version
                        """)
                .param("user", userId)
                .param("version", expectedVersion)
                .param("app", appId)
                .param("digest", digest)
                .param("nickname", nickname)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> conflict("staleRecord"));
    }

    private void lockIdentity(String appId, String digest) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:identity,0))")
                .param("identity", appId + ":" + digest)
                .query(
                        resultSet -> {
                            resultSet.next();
                            return 0;
                        });
    }

    private UUID findIdentityOwner(String appId, String digest) {
        return jdbc.sql(
                        "SELECT user_id FROM platform_wechat_binding WHERE app_id=:app AND open_id_digest=:digest")
                .param("app", appId)
                .param("digest", digest)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private boolean activeAccount(UUID userId) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_user WHERE id=:id AND enabled=true AND deleted_at IS NULL)")
                .param("id", userId)
                .query(Boolean.class)
                .single();
    }

    private Optional<Attempt> findAttempt(
            UUID id, String sessionDigest, String purpose, boolean lock) {
        return jdbc.sql(
                        "SELECT "
                                + ATTEMPT_COLUMNS
                                + " FROM platform_wechat_attempt WHERE id=:id AND session_digest=:session AND purpose=:purpose"
                                + (lock ? " FOR UPDATE" : ""))
                .param("id", id)
                .param("session", sessionDigest)
                .param("purpose", purpose)
                .query(WechatIdentityService::mapAttempt)
                .optional();
    }

    private static Attempt mapAttempt(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        long bindingVersion = rs.getLong("base_binding_version");
        Long nullableBindingVersion = rs.wasNull() ? null : bindingVersion;
        return new Attempt(
                rs.getObject("id", UUID.class),
                rs.getString("purpose"),
                rs.getString("state"),
                rs.getString("session_digest"),
                rs.getLong("settings_version"),
                rs.getString("app_id"),
                rs.getObject("contact_proof_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                nullableBindingVersion,
                rs.getBoolean("replace_binding"),
                rs.getObject("authentication_revision", UUID.class),
                rs.getObject("primary_credential_revision", UUID.class),
                rs.getObject("factor_credential_revision", UUID.class),
                rs.getString("open_id_digest"),
                rs.getString("nickname"),
                rs.getObject("registration_proof_id", UUID.class),
                rs.getString("login_json"),
                rs.getString("failure_code"),
                rs.getObject("expires_at", OffsetDateTime.class));
    }

    private void validateReadyBindAttempt(Attempt attempt, UUID userId, long expectedVersion) {
        if (!"READY".equals(attempt.state())
                || !attempt.expiresAt().isAfter(OffsetDateTime.now())
                || !"BIND".equals(attempt.purpose())
                || !userId.equals(attempt.userId())
                || attempt.baseBindingVersion() == null
                || attempt.baseBindingVersion() != expectedVersion
                || attempt.openIdDigest() == null) throw conflict("wechatUnavailable");
    }

    private boolean sameAuthenticationRevision(
            Attempt attempt, EffectiveAuthenticationPolicyPort.EffectivePolicy current) {
        return attempt.authenticationRevision() != null
                && attempt.authenticationRevision().equals(current.revision())
                && attempt.primaryCredentialRevision() != null
                && attempt.primaryCredentialRevision().equals(current.primaryCredentialRevision())
                && attempt.factorCredentialRevision() != null
                && attempt.factorCredentialRevision().equals(current.factorCredentialRevision());
    }

    private boolean retainCurrentSession(
            HttpServletRequest request,
            EffectiveAuthenticationPolicyPort.EffectivePolicy effective) {
        HttpSession session = request.getSession(false);
        if (session == null) return false;
        Object methodValue = session.getAttribute(AuthenticationSession.PRIMARY_METHOD);
        if (!(methodValue instanceof String methodName)) return false;
        final PrimaryMethod method;
        try {
            method = PrimaryMethod.valueOf(methodName);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        if (method == PrimaryMethod.WECHAT
                || !effective.allowedMethods().contains(method)
                || !effective.usableMethods().contains(method)) return false;
        List<String> factors =
                AuthenticationSession.factors(
                        session.getAttribute(AuthenticationSession.VERIFIED_FACTORS));
        return !effective.mfaRequired()
                || factors.stream()
                        .anyMatch(
                                factor -> factor.equals("TOTP") || factor.equals("RECOVERY_CODE"));
    }

    private void refreshOrInvalidateCurrentSession(
            HttpServletRequest request, BindingMutation mutation) {
        HttpSession session = request.getSession(false);
        if (!mutation.preserveCurrentSession() || session == null) {
            SecurityContextHolder.clearContext();
            if (session != null) {
                session.removeAttribute(
                        org.springframework.security.web.context
                                .HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                try {
                    session.invalidate();
                } catch (IllegalStateException alreadyInvalidated) {
                    // Another authentication transition invalidated the session during this
                    // request.
                }
            }
            return;
        }
        var effective = mutation.effective();
        String revision = effective.revision().toString();
        session.setAttribute(AuthenticationSession.AUTHENTICATION_REVISION, revision);
        session.setAttribute(SecondFactorSession.REQUIREMENT_VERSION_ATTRIBUTE, revision);
        session.setAttribute(
                AuthenticationSession.PRIMARY_CREDENTIAL_REVISION,
                effective.primaryCredentialRevision().toString());
        session.setAttribute(
                AuthenticationSession.FACTOR_CREDENTIAL_REVISION,
                effective.factorCredentialRevision().toString());
    }

    private void requirePublicProvider() {
        if (!settings.ready()
                || !globalPolicy.current().enabledMethods().contains(PrimaryMethod.WECHAT))
            throw unavailable("wechatUnavailable");
    }

    private static String sessionId(HttpServletRequest request, boolean create) {
        HttpSession session = create ? request.getSession(true) : request.getSession(false);
        return session == null ? null : session.getId();
    }

    private static void requireCompleteAuthentication(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null
                || !(session.getAttribute(AuthenticationSession.PRIMARY_METHOD) instanceof String))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "reauthenticationRequired");
        Object contextValue =
                session.getAttribute(
                        org.springframework.security.web.context
                                .HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(contextValue
                        instanceof
                        org.springframework.security.core.context.SecurityContext context)
                || context.getAuthentication() == null
                || !context.getAuthentication().isAuthenticated()
                || context.getAuthentication()
                        instanceof
                        org.springframework.security.authentication.AnonymousAuthenticationToken)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "reauthenticationRequired");
    }

    private static UUID parseUuid(String value, String errorCode) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException invalid) {
            throw badRequest(errorCode);
        }
    }

    private static String randomState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String authorizationUrl(String appId, String publicOrigin, String rawState) {
        String callback = publicOrigin + "/api/auth/wechat/callback";
        return "https://open.weixin.qq.com/connect/qrconnect?appid="
                + encode(appId)
                + "&redirect_uri="
                + encode(callback)
                + "&response_type=code&scope=snsapi_login&state="
                + encode(rawState)
                + "#wechat_redirect";
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String identityDigest(String appId, String openId) {
        if (openId == null
                || openId.isBlank()
                || openId.length() > 256
                || openId.codePoints().anyMatch(Character::isISOControl))
            throw unavailable("wechatProviderUnavailable");
        byte[] value = openId.getBytes(StandardCharsets.UTF_8);
        try {
            return crypto.fingerprint("wechat:" + appId, value);
        } finally {
            java.util.Arrays.fill(value, (byte) 0);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.US_ASCII));
            try {
                return HexFormat.of().formatHex(digest);
            } finally {
                java.util.Arrays.fill(digest, (byte) 0);
            }
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String publicState(Attempt attempt) {
        if (!attempt.expiresAt().isAfter(OffsetDateTime.now())
                && Set.of("PENDING", "CLAIMED", "READY").contains(attempt.state()))
            return "EXPIRED";
        return switch (attempt.state()) {
            case "PENDING", "CLAIMED", "AUTHENTICATING" -> "PENDING";
            case "READY" -> "READY";
            case "UNBOUND" -> "UNBOUND";
            case "FAILED" -> "FAILED";
            case "EXPIRED" -> "EXPIRED";
            case "CANCELLED", "CONSUMED", "SUPERSEDED" -> "CANCELLED";
            default -> "FAILED";
        };
    }

    private static String safeFailure(String failure) {
        return Set.of(
                                "wechatUnavailable",
                                "wechatProviderUnavailable",
                                "wechatLoginFailed",
                                "wechatAlreadyUsed",
                                "wechatRegistrationUnavailable",
                                "wechatRegistrationFailed")
                        .contains(failure)
                ? failure
                : "wechatProviderUnavailable";
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

    private record UserPrimary(UUID id, String username, UUID primaryCredentialRevision) {}
}
