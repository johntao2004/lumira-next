package com.lumira.platform.identity;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.*;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** One persistent, policy-aware path for every primary authentication method. */
@Service
public class SecondFactorLoginService implements PrimaryAuthenticationPort {
    static final String PENDING = "lumira.login.challenge";

    private record Challenge(
            UUID userId,
            String username,
            String provider,
            UUID requirementVersion,
            UUID policyRevision,
            PrimaryMethod primaryMethod,
            String state,
            int attempts,
            Instant expiresAt,
            String captchaPolicyRevision,
            boolean captchaSatisfied) {}

    private record PrimaryResult(
            LoginResponse response,
            String username,
            UUID userId,
            UUID revision,
            String captchaPolicyRevision) {}

    private record ConfirmationResult(
            String username,
            UUID userId,
            UUID policyRevision,
            String captchaPolicyRevision,
            boolean captchaSatisfied,
            PrimaryMethod primaryMethod,
            String factor,
            String failure) {
        ConfirmationResult(
                String username,
                UUID userId,
                UUID policyRevision,
                PrimaryMethod primaryMethod,
                String factor,
                String failure) {
            this(username, userId, policyRevision, null, false, primaryMethod, factor, failure);
        }
    }

    private record EnrollmentResult(
            String username,
            UUID userId,
            UUID policyRevision,
            String captchaPolicyRevision,
            boolean captchaSatisfied,
            PrimaryMethod primaryMethod,
            List<String> recoveryCodes,
            String failure) {
        EnrollmentResult(
                String username,
                UUID userId,
                UUID policyRevision,
                PrimaryMethod primaryMethod,
                List<String> recoveryCodes,
                String failure) {
            this(
                    username,
                    userId,
                    policyRevision,
                    null,
                    false,
                    primaryMethod,
                    recoveryCodes,
                    failure);
        }
    }

    private record CaptchaConfirmation(
            String username,
            UUID userId,
            UUID policyRevision,
            String captchaPolicyRevision,
            PrimaryMethod primaryMethod,
            LoginResponse response,
            String failure) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PluginExtensionsPort plugins;
    private final UserDetailsService users;
    private final MaintenancePolicyPort maintenance;
    private final LoginAudit audit;
    private final ClientAddressResolver addresses;
    private final FixedWindowBuckets buckets;
    private final AuthenticationPolicyWriteLock policyLock;
    private final EffectiveAuthenticationPolicyPort policies;
    private final AccountSecurityPort accountSecurity;
    private final PasswordCredentialProof passwordProofs;
    private final CaptchaProofAccess captcha;
    private final MfaRecoveryTicketPort recoveryTickets;
    private final TemporaryPasswordResetService temporaryPasswords;

    @org.springframework.beans.factory.annotation.Autowired
    public SecondFactorLoginService(
            JdbcClient jdbc,
            TransactionTemplate tx,
            PluginExtensionsPort plugins,
            UserDetailsService users,
            MaintenancePolicyPort maintenance,
            LoginAudit audit,
            ClientAddressResolver addresses,
            FixedWindowBuckets buckets,
            AuthenticationPolicyWriteLock policyLock,
            EffectiveAuthenticationPolicyPort policies,
            AccountSecurityPort accountSecurity,
            PasswordCredentialProof passwordProofs,
            CaptchaProofAccess captcha,
            MfaRecoveryTicketPort recoveryTickets,
            TemporaryPasswordResetService temporaryPasswords) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.plugins = plugins;
        this.users = users;
        this.maintenance = maintenance;
        this.audit = audit;
        this.addresses = addresses;
        this.buckets = buckets;
        this.policyLock = policyLock;
        this.policies = policies;
        this.accountSecurity = accountSecurity;
        this.passwordProofs = passwordProofs;
        this.captcha = captcha;
        this.recoveryTickets = recoveryTickets;
        this.temporaryPasswords = temporaryPasswords;
    }

    /** Compatibility constructor retained for focused authentication-flow tests. */
    public SecondFactorLoginService(
            JdbcClient jdbc,
            TransactionTemplate tx,
            PluginExtensionsPort plugins,
            UserDetailsService users,
            MaintenancePolicyPort maintenance,
            LoginAudit audit,
            ClientAddressResolver addresses,
            FixedWindowBuckets buckets,
            AuthenticationPolicyWriteLock policyLock,
            EffectiveAuthenticationPolicyPort policies,
            AccountSecurityPort accountSecurity,
            PasswordCredentialProof passwordProofs,
            CaptchaProofAccess captcha,
            MfaRecoveryTicketPort recoveryTickets) {
        this(
                jdbc,
                tx,
                plugins,
                users,
                maintenance,
                audit,
                addresses,
                buckets,
                policyLock,
                policies,
                accountSecurity,
                passwordProofs,
                captcha,
                recoveryTickets,
                null);
    }

    public LoginResponse primary(
            Authentication auth, HttpServletRequest req, HttpServletResponse res) {
        return primary(auth, PrimaryMethod.PASSWORD, null, req, res);
    }

    @Override
    public LoginResponse complete(
            Authentication auth,
            PrimaryMethod method,
            String primaryCredentialRevision,
            HttpServletRequest req,
            HttpServletResponse res) {
        if (method == null) throw failure("authenticationMethodInvalid");
        return primary(auth, method, primaryCredentialRevision, req, res);
    }

    public LoginResponse primary(
            Authentication auth,
            PrimaryMethod method,
            HttpServletRequest req,
            HttpServletResponse res) {
        return primary(auth, method, null, req, res);
    }

    private LoginResponse primary(
            Authentication auth,
            PrimaryMethod method,
            String primaryCredentialRevision,
            HttpServletRequest req,
            HttpServletResponse res) {
        SecurityContextHolder.clearContext();
        var session = req.getSession();
        session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        cancel(req);
        req.changeSessionId();
        PrimaryResult result =
                tx.execute(
                        status -> {
                            policyLock.lockForAuthentication();
                            UUID userId = lockUserByName(auth.getName());
                            if (!maintenance.canLogin(auth.getName()))
                                throw unavailable("maintenanceActive");
                            PasswordCredentialProof.Evidence passwordEvidence = null;
                            if (method == PrimaryMethod.PASSWORD)
                                passwordEvidence = verifyPasswordProof(auth, userId);
                            var effective = policies.resolve(userId);
                            if (effective.usableMethods().isEmpty())
                                throw conflict("authenticationPolicyConflict");
                            if (!effective.allowedMethods().contains(method))
                                throw conflict("authenticationMethodDisabled");
                            if (!effective.usableMethods().contains(method))
                                throw conflict("authenticationMethodUnavailable");
                            String observedPrimaryRevision =
                                    method == PrimaryMethod.PASSWORD
                                            ? passwordEvidence
                                                    .primaryCredentialRevision()
                                                    .toString()
                                            : primaryCredentialRevision;
                            if (observedPrimaryRevision == null
                                    || !observedPrimaryRevision.equals(
                                            effective.primaryCredentialRevision().toString()))
                                throw conflict("authenticationProofExpired");
                            if (temporaryPasswords != null) {
                                var reset = temporaryPasswords.lockRequirement(userId);
                                if (reset != null
                                        && reset.credentialRevision()
                                                .equals(effective.primaryCredentialRevision())) {
                                    if (method == PrimaryMethod.PASSWORD) {
                                        if (!reset.active())
                                            throw failure("temporaryPasswordExpired");
                                        if (reset.claimedAt() != null)
                                            throw failure("temporaryPasswordUsed");
                                    }
                                    if (reset.active()
                                            && reset.claimedAt() == null
                                            && !temporaryPasswords.claim(
                                                    userId, effective.primaryCredentialRevision()))
                                        throw failure("temporaryPasswordUsed");
                                    Instant expires =
                                            reset.active()
                                                    ? reset.expiresAt()
                                                    : Instant.now().plusSeconds(300);
                                    UUID challenge = UUID.randomUUID();
                                    jdbc.sql(
                                                    "INSERT INTO platform_login_challenge(id,user_id,provider_id,session_hash,requirement_version,primary_method,state,policy_revision,captcha_policy_revision,captcha_satisfied,expires_at) VALUES(:id,:user,'password-change',:session,NULL,:method,'PASSWORD_CHANGE_REQUIRED',:revision,NULL,true,:expires)")
                                            .param("id", challenge)
                                            .param("user", userId)
                                            .param("session", sessionHash(req))
                                            .param("method", method.name())
                                            .param("revision", effective.revision())
                                            .param("expires", java.sql.Timestamp.from(expires))
                                            .update();
                                    req.getSession().setAttribute(PENDING, challenge.toString());
                                    return new PrimaryResult(
                                            pendingResponse(
                                                    challenge,
                                                    expires,
                                                    "PASSWORD_CHANGE_REQUIRED",
                                                    effective.revision()),
                                            auth.getName(),
                                            userId,
                                            effective.revision(),
                                            null);
                                } else if (reset != null) {
                                    temporaryPasswords.discardObsolete(
                                            userId, effective.primaryCredentialRevision());
                                }
                            }
                            var captchaGate =
                                    captcha.begin(
                                            CaptchaProofPort.Action.POST_PRIMARY_AUTH,
                                            userId,
                                            null,
                                            req);
                            if (!effective.mfaRequired() && !captchaGate.requirement().required())
                                return new PrimaryResult(
                                        null,
                                        auth.getName(),
                                        userId,
                                        effective.revision(),
                                        captchaGate.requirement().policyRevision());

                            String state = "CAPTCHA_REQUIRED";
                            var requirement = java.util.Optional.<Requirement>empty();
                            if (!captchaGate.requirement().required()) {
                                requirement =
                                        jdbc.sql(
                                                        """
                                                    SELECT provider_id,requirement_version,self_enabled,recovery_pending
                                                      FROM platform_second_factor_requirement WHERE user_id=:id
                                                    """)
                                                .param("id", userId)
                                                .query(
                                                        (rs, row) ->
                                                                new Requirement(
                                                                        rs.getString("provider_id"),
                                                                        rs.getObject(
                                                                                "requirement_version",
                                                                                UUID.class),
                                                                        rs.getBoolean(
                                                                                "self_enabled"),
                                                                        rs.getBoolean(
                                                                                "recovery_pending")))
                                                .optional();
                                var factorStatus =
                                        withProvider(provider -> provider.status(userId));
                                if (factorStatus == null)
                                    throw unavailable("secondFactorUnavailable");
                                state =
                                        challengeState(
                                                requirement
                                                        .map(Requirement::recoveryPending)
                                                        .orElse(false),
                                                requirement
                                                        .map(Requirement::selfEnabled)
                                                        .orElse(false),
                                                factorStatus.configured());
                            }
                            UUID challenge = UUID.randomUUID();
                            Instant expires = Instant.now().plusSeconds(300);
                            jdbc.sql(
                                            """
                                            INSERT INTO platform_login_challenge
                                                (id,user_id,provider_id,session_hash,requirement_version,
                                                 primary_method,state,policy_revision,captcha_policy_revision,
                                                 captcha_satisfied,expires_at)
                                            VALUES (:id,:user,:provider,:session,:requirementVersion,
                                                    :method,:state,:revision,:captchaRevision,false,:expires)
                                            """)
                                    .param("id", challenge)
                                    .param("user", userId)
                                    .param(
                                            "provider",
                                            captchaGate.requirement().required()
                                                    ? "captcha"
                                                    : "mfa")
                                    .param("session", sessionHash(req))
                                    .param(
                                            "requirementVersion",
                                            requirement.map(Requirement::version).orElse(null))
                                    .param("method", method.name())
                                    .param("state", state)
                                    .param("revision", effective.revision())
                                    .param(
                                            "captchaRevision",
                                            captchaGate.requirement().policyRevision())
                                    .param("expires", java.sql.Timestamp.from(expires))
                                    .update();
                            req.getSession().setAttribute(PENDING, challenge.toString());
                            return new PrimaryResult(
                                    pendingResponse(
                                            challenge, expires, state, effective.revision()),
                                    auth.getName(),
                                    userId,
                                    effective.revision(),
                                    captchaGate.requirement().policyRevision());
                        });
        if (result.response() != null) return result.response();
        return finish(
                result.username(),
                result.userId(),
                method,
                List.of(),
                result.revision(),
                result.captchaPolicyRevision(),
                false,
                req,
                res);
    }

    public LoginResponse pending(HttpServletRequest req) {
        String value = pendingId(req);
        if (value == null) return noPending("NONE");
        UUID challengeId;
        try {
            challengeId = UUID.fromString(value);
        } catch (IllegalArgumentException invalidSessionValue) {
            var session = req.getSession(false);
            if (session != null) session.removeAttribute(PENDING);
            return noPending("EXPIRED");
        }
        return tx.execute(
                status -> {
                    policyLock.lockForAuthentication();
                    var challenge =
                            jdbc.sql(
                                            """
                                            SELECT c.id,c.user_id,c.expires_at,c.state,c.primary_method,c.policy_revision
                                                  ,c.captcha_policy_revision,c.captcha_satisfied
                              FROM platform_login_challenge c
                             WHERE c.id=:id AND c.session_hash=:session
                               AND c.expires_at>CURRENT_TIMESTAMP AND c.attempts<5
                            """)
                                    .param("id", challengeId)
                                    .param("session", sessionHash(req))
                                    .query(
                                            (rs, row) ->
                                                    new Pending(
                                                            rs.getObject("id", UUID.class),
                                                            rs.getObject("user_id", UUID.class),
                                                            rs.getTimestamp("expires_at")
                                                                    .toInstant(),
                                                            rs.getString("state"),
                                                            PrimaryMethod.valueOf(
                                                                    rs.getString("primary_method")),
                                                            rs.getObject(
                                                                    "policy_revision", UUID.class),
                                                            rs.getString("captcha_policy_revision"),
                                                            rs.getBoolean("captcha_satisfied")))
                                    .optional();
                    if (challenge.isEmpty()) {
                        deleteChallenge(challengeId, req);
                        clearPending(req, challengeId);
                        return noPending("EXPIRED");
                    }
                    var current = policies.resolve(challenge.get().userId());
                    if (!current.revision().equals(challenge.get().revision())) {
                        deleteChallenge(challenge.get().id(), req);
                        clearPending(req, challengeId);
                        return noPending("EXPIRED");
                    }
                    if (!"PASSWORD_CHANGE_REQUIRED".equals(challenge.get().state())
                            && !captchaSatisfactionCurrent(
                                    challenge.get().userId(),
                                    challenge.get().captchaPolicyRevision(),
                                    challenge.get().captchaSatisfied(),
                                    challenge.get().state(),
                                    req)) {
                        deleteChallenge(challenge.get().id(), req);
                        clearPending(req, challengeId);
                        return noPending("EXPIRED");
                    }
                    return pendingResponse(
                            challenge.get().id(),
                            challenge.get().expiresAt(),
                            challenge.get().state(),
                            challenge.get().revision());
                });
    }

    /** Consumes the action-bound CAPTCHA proof and advances only this session's primary attempt. */
    public LoginResponse confirmCaptcha(
            UUID challengeId,
            String proofId,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (challengeId == null || !challengeId.toString().equals(pendingId(request)))
            throw failure("secondFactorExpired");

        CaptchaConfirmation result =
                tx.execute(
                        status -> {
                            policyLock.lockForAuthentication();
                            UUID userId = challengeUser(challengeId, request);
                            if (userId == null)
                                return new CaptchaConfirmation(
                                        null, null, null, null, null, null, "secondFactorExpired");
                            String username = lockUser(userId);
                            Challenge current =
                                    lockChallenge(challengeId, request, userId, username);
                            if (current == null || !current.state().equals("CAPTCHA_REQUIRED"))
                                return new CaptchaConfirmation(
                                        username,
                                        userId,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "secondFactorExpired");
                            if (current.attempts() >= 5)
                                return new CaptchaConfirmation(
                                        username,
                                        userId,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "secondFactorLocked");

                            var effective = policies.resolve(userId);
                            if (!effective.revision().equals(current.policyRevision())
                                    || !effective.allowedMethods().contains(current.primaryMethod())
                                    || !effective
                                            .usableMethods()
                                            .contains(current.primaryMethod())) {
                                deleteChallenge(challengeId, request);
                                return new CaptchaConfirmation(
                                        username,
                                        userId,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "secondFactorExpired");
                            }

                            CaptchaProofAccess.Gate gate =
                                    captcha.begin(
                                            CaptchaProofPort.Action.POST_PRIMARY_AUTH,
                                            userId,
                                            null,
                                            request);
                            if (!gate.requirement().required()
                                    || !Objects.equals(
                                            gate.requirement().policyRevision(),
                                            current.captchaPolicyRevision())) {
                                deleteChallenge(challengeId, request);
                                return new CaptchaConfirmation(
                                        username,
                                        userId,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "secondFactorExpired");
                            }
                            try {
                                captcha.consume(gate, proofId, request, challengeId.toString());
                            } catch (ResponseStatusException invalidProof) {
                                incrementAttempts(challengeId);
                                return new CaptchaConfirmation(
                                        username,
                                        userId,
                                        null,
                                        null,
                                        null,
                                        null,
                                        invalidProof.getReason() == null
                                                ? "captchaRequired"
                                                : invalidProof.getReason());
                            }

                            if (!maintenance.canLogin(username))
                                throw unavailable("maintenanceActive");
                            String nextState = null;
                            if (effective.mfaRequired()) {
                                var requirement =
                                        jdbc.sql(
                                                        """
                                SELECT requirement_version,self_enabled,recovery_pending
                                  FROM platform_second_factor_requirement WHERE user_id=:id
                                """)
                                                .param("id", userId)
                                                .query(
                                                        (rs, row) ->
                                                                new Requirement(
                                                                        "mfa",
                                                                        rs.getObject(
                                                                                "requirement_version",
                                                                                UUID.class),
                                                                        rs.getBoolean(
                                                                                "self_enabled"),
                                                                        rs.getBoolean(
                                                                                "recovery_pending")))
                                                .optional();
                                var factorStatus =
                                        withProvider(provider -> provider.status(userId));
                                if (factorStatus == null)
                                    throw unavailable("secondFactorUnavailable");
                                nextState =
                                        challengeState(
                                                requirement
                                                        .map(Requirement::recoveryPending)
                                                        .orElse(false),
                                                requirement
                                                        .map(Requirement::selfEnabled)
                                                        .orElse(false),
                                                factorStatus.configured());
                            }

                            if (nextState == null) {
                                deleteChallenge(challengeId, request);
                                return new CaptchaConfirmation(
                                        username,
                                        userId,
                                        effective.revision(),
                                        gate.requirement().policyRevision(),
                                        current.primaryMethod(),
                                        null,
                                        null);
                            }
                            jdbc.sql(
                                            """
                            UPDATE platform_login_challenge
                               SET provider_id='mfa',state=:state,captcha_satisfied=true
                             WHERE id=:id AND state='CAPTCHA_REQUIRED'
                            """)
                                    .param("state", nextState)
                                    .param("id", challengeId)
                                    .update();
                            return new CaptchaConfirmation(
                                    username,
                                    userId,
                                    effective.revision(),
                                    gate.requirement().policyRevision(),
                                    current.primaryMethod(),
                                    pendingResponse(
                                            challengeId,
                                            current.expiresAt(),
                                            nextState,
                                            effective.revision()),
                                    null);
                        });

        if (result.failure() != null) {
            if (result.failure().equals("secondFactorLocked")
                    || result.failure().equals("secondFactorExpired"))
                clearPending(request, challengeId);
            throw failure(result.failure());
        }
        if (result.response() != null) return result.response();
        clearPending(request, challengeId);
        return finish(
                result.username(),
                result.userId(),
                result.primaryMethod(),
                List.of(),
                result.policyRevision(),
                result.captchaPolicyRevision(),
                true,
                request,
                response);
    }

    public LoginResponse confirm(
            UUID challenge, String code, HttpServletRequest req, HttpServletResponse res) {
        if (challenge == null || !challenge.toString().equals(pendingId(req)))
            throw failure("secondFactorExpired");
        if (code == null || code.length() > 64) throw failure("secondFactorInvalid");
        if (!buckets.consume(FixedWindowBuckets.key("mfa-source", addresses.resolve(req)), 60))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "loginRateLimited");

        ConfirmationResult outcome =
                tx.execute(
                        status -> {
                            policyLock.lockForAuthentication();
                            UUID userId = challengeUser(challenge, req);
                            if (userId == null)
                                return new ConfirmationResult(
                                        null, null, null, null, null, "secondFactorExpired");
                            String username = lockUser(userId);
                            Challenge current = lockChallenge(challenge, req, userId, username);
                            if (current == null)
                                return new ConfirmationResult(
                                        username, userId, null, null, null, "secondFactorExpired");
                            if (current.attempts() >= 5)
                                return new ConfirmationResult(
                                        username,
                                        userId,
                                        null,
                                        current.primaryMethod(),
                                        null,
                                        "secondFactorLocked");
                            if (!current.state().equals("MFA_REQUIRED"))
                                return new ConfirmationResult(
                                        username,
                                        userId,
                                        null,
                                        current.primaryMethod(),
                                        null,
                                        "secondFactorExpired");
                            var effective = policies.resolve(userId);
                            if (!effective.mfaRequired()
                                    || !effective.revision().equals(current.policyRevision())
                                    || !effective.allowedMethods().contains(current.primaryMethod())
                                    || !effective
                                            .usableMethods()
                                            .contains(current.primaryMethod())) {
                                deleteChallenge(challenge);
                                return new ConfirmationResult(
                                        username,
                                        userId,
                                        null,
                                        current.primaryMethod(),
                                        null,
                                        "secondFactorExpired");
                            }
                            if (!captchaSatisfactionCurrent(
                                    userId,
                                    current.captchaPolicyRevision(),
                                    current.captchaSatisfied(),
                                    current.state(),
                                    req)) {
                                deleteChallenge(challenge);
                                return new ConfirmationResult(
                                        username,
                                        userId,
                                        null,
                                        current.primaryMethod(),
                                        null,
                                        "secondFactorExpired");
                            }
                            if (!maintenance.canLogin(username))
                                throw unavailable("maintenanceActive");
                            if (!buckets.consume(
                                    FixedWindowBuckets.key("mfa-account", userId.toString()), 15))
                                return new ConfirmationResult(
                                        username,
                                        userId,
                                        null,
                                        current.primaryMethod(),
                                        null,
                                        "loginRateLimited");
                            String factor =
                                    withProvider(provider -> provider.verifyEvidence(userId, code));
                            if (factor == null) {
                                incrementAttempts(challenge);
                                return new ConfirmationResult(
                                        username,
                                        userId,
                                        null,
                                        current.primaryMethod(),
                                        null,
                                        "secondFactorInvalid");
                            }
                            var verifiedPolicy = effective;
                            if ("RECOVERY_CODE".equals(factor)) {
                                // Consuming a recovery code rotates the account factor epoch. The
                                // proof that caused that rotation may finish this one login, but
                                // no unrelated policy or primary-credential change may be folded
                                // into the new session.
                                var afterFactorRotation = policies.resolve(userId);
                                if (!AuthenticationPolicyComparison.sameExceptFactorEpoch(
                                                effective, afterFactorRotation)
                                        || effective
                                                .factorCredentialRevision()
                                                .equals(
                                                        afterFactorRotation
                                                                .factorCredentialRevision())) {
                                    deleteChallenge(challenge);
                                    return new ConfirmationResult(
                                            username,
                                            userId,
                                            null,
                                            current.primaryMethod(),
                                            null,
                                            "secondFactorExpired");
                                }
                                verifiedPolicy = afterFactorRotation;
                            }
                            deleteChallenge(challenge);
                            return new ConfirmationResult(
                                    username,
                                    userId,
                                    verifiedPolicy.revision(),
                                    current.captchaPolicyRevision(),
                                    current.captchaSatisfied(),
                                    current.primaryMethod(),
                                    factor,
                                    null);
                        });
        if (outcome.failure() != null) {
            String reason = outcome.failure();
            if (reason.equals("secondFactorInvalid"))
                audit.record(
                        outcome.username(),
                        false,
                        addresses.resolve(req),
                        req.getHeader("User-Agent"));
            if (reason.equals("secondFactorExpired") || reason.equals("secondFactorLocked")) {
                var session = req.getSession(false);
                if (session != null) session.removeAttribute(PENDING);
            }
            if (reason.equals("loginRateLimited"))
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, reason);
            throw failure(reason);
        }
        req.getSession().removeAttribute(PENDING);
        return finish(
                outcome.username(),
                outcome.userId(),
                outcome.primaryMethod(),
                List.of(outcome.factor()),
                outcome.policyRevision(),
                outcome.captchaPolicyRevision(),
                outcome.captchaSatisfied(),
                req,
                res);
    }

    public SecondFactorProvider.EnrollmentSetup startEnrollment(
            UUID challengeId, String label, HttpServletRequest request) {
        if (!policies.secondFactorAvailable()) throw unavailable("secondFactorUnavailable");
        if (challengeId == null || !challengeId.toString().equals(pendingId(request)))
            throw failure("secondFactorExpired");
        return tx.execute(
                status -> {
                    policyLock.lockForAuthentication();
                    UUID userId = challengeUser(challengeId, request);
                    if (userId == null) throw failure("secondFactorExpired");
                    String username = lockUser(userId);
                    Challenge challenge = lockChallenge(challengeId, request, userId, username);
                    if (challenge == null || !challenge.state().equals("ENROLLMENT_REQUIRED"))
                        throw failure("secondFactorEnrollmentUnavailable");
                    if (challenge.attempts() >= 5) throw failure("secondFactorLocked");
                    var effective = policies.resolve(userId);
                    if (!effective.revision().equals(challenge.policyRevision())
                            || !effective.mfaRequired()
                            || !effective.allowedMethods().contains(challenge.primaryMethod())
                            || !effective.usableMethods().contains(challenge.primaryMethod()))
                        throw failure("secondFactorExpired");
                    if (!captchaSatisfactionCurrent(
                            userId,
                            challenge.captchaPolicyRevision(),
                            challenge.captchaSatisfied(),
                            challenge.state(),
                            request)) throw failure("secondFactorExpired");
                    if (!maintenance.canLogin(username)) throw unavailable("maintenanceActive");
                    return withProvider(
                            provider ->
                                    provider.startEnrollment(
                                            userId,
                                            username,
                                            label,
                                            challengeId,
                                            sessionHash(request)));
                });
    }

    public record EnrollmentConfirmationResponse(LoginResponse login, List<String> recoveryCodes) {
        public EnrollmentConfirmationResponse {
            recoveryCodes = List.copyOf(recoveryCodes);
        }
    }

    public EnrollmentConfirmationResponse confirmEnrollment(
            UUID challengeId,
            String code,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (!policies.secondFactorAvailable()) throw unavailable("secondFactorUnavailable");
        if (challengeId == null || !challengeId.toString().equals(pendingId(request)))
            throw failure("secondFactorExpired");
        if (code == null || code.length() > 64) throw failure("secondFactorInvalid");
        EnrollmentResult result =
                tx.execute(
                        status -> {
                            policyLock.lockForAuthentication();
                            UUID userId = challengeUser(challengeId, request);
                            if (userId == null)
                                return new EnrollmentResult(
                                        null, null, null, null, List.of(), "secondFactorExpired");
                            String username = lockUser(userId);
                            Challenge challenge =
                                    lockChallenge(challengeId, request, userId, username);
                            if (challenge == null
                                    || !challenge.state().equals("ENROLLMENT_REQUIRED"))
                                return new EnrollmentResult(
                                        username,
                                        userId,
                                        null,
                                        null,
                                        List.of(),
                                        "secondFactorEnrollmentUnavailable");
                            if (challenge.attempts() >= 5)
                                return new EnrollmentResult(
                                        username,
                                        userId,
                                        null,
                                        challenge.primaryMethod(),
                                        List.of(),
                                        "secondFactorLocked");
                            var effective = policies.resolve(userId);
                            if (!effective.revision().equals(challenge.policyRevision())
                                    || !effective.mfaRequired()
                                    || !effective
                                            .allowedMethods()
                                            .contains(challenge.primaryMethod())
                                    || !effective
                                            .usableMethods()
                                            .contains(challenge.primaryMethod())) {
                                deleteChallenge(challengeId);
                                return new EnrollmentResult(
                                        username,
                                        userId,
                                        null,
                                        challenge.primaryMethod(),
                                        List.of(),
                                        "secondFactorExpired");
                            }
                            if (!captchaSatisfactionCurrent(
                                    userId,
                                    challenge.captchaPolicyRevision(),
                                    challenge.captchaSatisfied(),
                                    challenge.state(),
                                    request)) {
                                deleteChallenge(challengeId);
                                return new EnrollmentResult(
                                        username,
                                        userId,
                                        null,
                                        challenge.primaryMethod(),
                                        List.of(),
                                        "secondFactorExpired");
                            }
                            if (!maintenance.canLogin(username))
                                throw unavailable("maintenanceActive");
                            var confirmation =
                                    withProvider(
                                            provider ->
                                                    provider.confirmEnrollment(
                                                            userId,
                                                            challengeId,
                                                            sessionHash(request),
                                                            code,
                                                            false));
                            if (confirmation == null) {
                                incrementAttempts(challengeId);
                                return new EnrollmentResult(
                                        username,
                                        userId,
                                        null,
                                        challenge.primaryMethod(),
                                        List.of(),
                                        "secondFactorInvalid");
                            }
                            accountSecurity.completeRecovery(userId, "mfa");
                            var completedPolicy = policies.resolve(userId);
                            if (!completedPolicy.mfaRequired()
                                    || !completedPolicy
                                            .allowedMethods()
                                            .contains(challenge.primaryMethod())
                                    || !completedPolicy
                                            .usableMethods()
                                            .contains(challenge.primaryMethod()))
                                return new EnrollmentResult(
                                        username,
                                        userId,
                                        null,
                                        challenge.primaryMethod(),
                                        List.of(),
                                        "secondFactorExpired");
                            deleteChallenge(challengeId);
                            return new EnrollmentResult(
                                    username,
                                    userId,
                                    completedPolicy.revision(),
                                    challenge.captchaPolicyRevision(),
                                    challenge.captchaSatisfied(),
                                    challenge.primaryMethod(),
                                    confirmation.recoveryCodes(),
                                    null);
                        });
        if (result.failure() != null) {
            if (result.failure().equals("secondFactorInvalid"))
                audit.record(
                        result.username(),
                        false,
                        addresses.resolve(request),
                        request.getHeader("User-Agent"));
            throw failure(result.failure());
        }
        request.getSession().removeAttribute(PENDING);
        LoginResponse login =
                finish(
                        result.username(),
                        result.userId(),
                        result.primaryMethod(),
                        List.of("TOTP"),
                        result.policyRevision(),
                        result.captchaPolicyRevision(),
                        result.captchaSatisfied(),
                        request,
                        response);
        response.setHeader("Cache-Control", "no-store");
        return new EnrollmentConfirmationResponse(login, result.recoveryCodes());
    }

    public void cancel(HttpServletRequest req) {
        String id = pendingId(req);
        if (id != null) {
            try {
                jdbc.sql(
                                "DELETE FROM platform_login_challenge WHERE id=:id AND session_hash=:session")
                        .param("id", UUID.fromString(id))
                        .param("session", sessionHash(req))
                        .update();
            } catch (IllegalArgumentException malformed) {
                // Drop malformed session state without allowing it to select another challenge.
            }
            var session = req.getSession(false);
            if (session != null) session.removeAttribute(PENDING);
        }
    }

    /** Cancels only the challenge still named by this browser session. */
    public void cancel(UUID challengeId, HttpServletRequest req) {
        var session = req.getSession(false);
        if (challengeId == null
                || session == null
                || !challengeId.toString().equals(pendingId(req)))
            throw conflict("secondFactorExpired");

        int deleted =
                tx.execute(
                        status -> {
                            policyLock.lockForAuthentication();
                            if (!challengeId.toString().equals(pendingId(req))) return 0;
                            return jdbc.sql(
                                            "DELETE FROM platform_login_challenge WHERE id=:id AND session_hash=:session")
                                    .param("id", challengeId)
                                    .param("session", sessionHash(req))
                                    .update();
                        });
        if (deleted != 1) {
            clearPending(req, challengeId);
            throw conflict("secondFactorExpired");
        }
        clearPending(req, challengeId);
    }

    public SecondFactorProvider.RecoveryRequest requestRecovery(
            UUID challengeId, HttpServletRequest request) {
        if (!policies.secondFactorAvailable()) throw unavailable("secondFactorUnavailable");
        if (challengeId == null || !challengeId.toString().equals(pendingId(request)))
            throw failure("secondFactorExpired");
        if (!buckets.consume(
                FixedWindowBuckets.key("mfarec-source", addresses.resolve(request)), 10))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "mfaRecoveryLimited");
        UUID userId =
                tx.execute(
                        status -> {
                            policyLock.lockForAuthentication();
                            UUID currentUser = challengeUser(challengeId, request);
                            if (currentUser == null) throw failure("secondFactorExpired");
                            String username = lockUser(currentUser);
                            Challenge current =
                                    lockChallenge(challengeId, request, currentUser, username);
                            if (current == null || !current.state().equals("RECOVERY_REQUIRED"))
                                throw failure("mfaRecoveryUnavailable");
                            var effective = policies.resolve(currentUser);
                            if (!effective.revision().equals(current.policyRevision())
                                    || !effective.mfaRequired()
                                    || !effective.allowedMethods().contains(current.primaryMethod())
                                    || !effective.usableMethods().contains(current.primaryMethod())
                                    || effective.requirementSources().stream()
                                            .noneMatch(
                                                    source ->
                                                            source.kind()
                                                                    .equals("RECOVERY_PENDING"))
                                    || !captchaSatisfactionCurrent(
                                            currentUser,
                                            current.captchaPolicyRevision(),
                                            current.captchaSatisfied(),
                                            current.state(),
                                            request)) throw failure("secondFactorExpired");
                            if (!maintenance.canLogin(username))
                                throw unavailable("maintenanceActive");
                            return currentUser;
                        });
        if (!buckets.consume(FixedWindowBuckets.key("mfarec-account", userId.toString()), 5))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "mfaRecoveryLimited");
        return withProvider(provider -> provider.requestRecovery(userId));
    }

    public LoginResponse verifyRecovery(
            UUID challengeId,
            String recoveryTicket,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (!policies.secondFactorAvailable()) throw unavailable("secondFactorUnavailable");
        if (challengeId == null || !challengeId.toString().equals(pendingId(request)))
            throw failure("secondFactorExpired");
        if (recoveryTicket == null || recoveryTicket.length() > 128)
            throw failure("secondFactorInvalid");
        if (!buckets.consume(
                FixedWindowBuckets.key("mfarec-source", addresses.resolve(request)), 10))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "mfaRecoveryLimited");

        record Outcome(String username, UUID userId, LoginResponse response, String failure) {}
        Outcome outcome =
                tx.execute(
                        status -> {
                            policyLock.lockForAuthentication();
                            UUID userId = challengeUser(challengeId, request);
                            if (userId == null)
                                return new Outcome(null, null, null, "secondFactorExpired");
                            String username = lockUser(userId);
                            Challenge current =
                                    lockChallenge(challengeId, request, userId, username);
                            if (current == null || !current.state().equals("RECOVERY_REQUIRED"))
                                return new Outcome(username, userId, null, "secondFactorExpired");
                            if (current.attempts() >= 5)
                                return new Outcome(username, userId, null, "secondFactorLocked");
                            var effective = policies.resolve(userId);
                            if (!effective.revision().equals(current.policyRevision())
                                    || !effective.mfaRequired()
                                    || !effective.allowedMethods().contains(current.primaryMethod())
                                    || !effective.usableMethods().contains(current.primaryMethod())
                                    || effective.requirementSources().stream()
                                            .noneMatch(
                                                    source ->
                                                            source.kind()
                                                                    .equals("RECOVERY_PENDING"))
                                    || !captchaSatisfactionCurrent(
                                            userId,
                                            current.captchaPolicyRevision(),
                                            current.captchaSatisfied(),
                                            current.state(),
                                            request)) {
                                deleteChallenge(challengeId, request);
                                return new Outcome(username, userId, null, "secondFactorExpired");
                            }
                            if (!maintenance.canLogin(username))
                                throw unavailable("maintenanceActive");
                            SecondFactorProvider.AccountStatus factorStatus =
                                    withProvider(provider -> provider.status(userId));
                            if (factorStatus == null || factorStatus.configured())
                                throw unavailable("secondFactorUnavailable");
                            MfaRecoveryTicketPort.ConsumeResult ticketResult =
                                    recoveryTickets.consume(userId, recoveryTicket);
                            if (ticketResult == MfaRecoveryTicketPort.ConsumeResult.LOCKED) {
                                return new Outcome(username, userId, null, "secondFactorLocked");
                            }
                            if (ticketResult != MfaRecoveryTicketPort.ConsumeResult.CONSUMED) {
                                incrementAttempts(challengeId);
                                return new Outcome(username, userId, null, "secondFactorInvalid");
                            }
                            int advanced =
                                    jdbc.sql(
                                                    """
                            UPDATE platform_login_challenge
                               SET state='ENROLLMENT_REQUIRED',provider_id='mfa'
                             WHERE id=:id AND user_id=:user AND state='RECOVERY_REQUIRED'
                            """)
                                            .param("id", challengeId)
                                            .param("user", userId)
                                            .update();
                            if (advanced != 1)
                                return new Outcome(username, userId, null, "secondFactorExpired");
                            return new Outcome(
                                    username,
                                    userId,
                                    pendingResponse(
                                            challengeId,
                                            current.expiresAt(),
                                            "ENROLLMENT_REQUIRED",
                                            effective.revision()),
                                    null);
                        });
        if (outcome.failure() != null) {
            if (outcome.failure().equals("secondFactorInvalid"))
                audit.record(
                        outcome.username(),
                        false,
                        addresses.resolve(request),
                        request.getHeader("User-Agent"));
            if (outcome.failure().equals("secondFactorExpired")
                    || outcome.failure().equals("secondFactorLocked"))
                clearPending(request, challengeId);
            throw failure(outcome.failure());
        }
        response.setHeader("Cache-Control", "no-store, max-age=0");
        return outcome.response();
    }

    private static void clearPending(HttpServletRequest req, UUID expected) {
        var session = req.getSession(false);
        if (session != null && expected.toString().equals(session.getAttribute(PENDING)))
            session.removeAttribute(PENDING);
    }

    private static LoginResponse noPending(String state) {
        return new LoginResponse(false, false, null, null, state, null, List.of(), null);
    }

    private LoginResponse finish(
            String name,
            UUID userId,
            PrimaryMethod method,
            List<String> verifiedFactors,
            UUID expectedRevision,
            String expectedCaptchaPolicyRevision,
            boolean captchaSatisfied,
            HttpServletRequest req,
            HttpServletResponse res) {
        EffectiveAuthenticationPolicyPort.EffectivePolicy effective =
                tx.execute(
                        status -> {
                            policyLock.lockForAuthentication();
                            lockUser(userId);
                            var current = policies.resolve(userId);
                            if (!current.revision().equals(expectedRevision)
                                    || !current.allowedMethods().contains(method)
                                    || !current.usableMethods().contains(method))
                                throw failure("reauthenticationRequired");
                            CaptchaProofAccess.Gate captchaGate =
                                    captcha.begin(
                                            CaptchaProofPort.Action.POST_PRIMARY_AUTH,
                                            userId,
                                            null,
                                            req);
                            if (!Objects.equals(
                                    expectedCaptchaPolicyRevision,
                                    captchaGate.requirement().policyRevision()))
                                throw failure("authenticationProofExpired");
                            if (captchaGate.requirement().required() && !captchaSatisfied)
                                throw failure("captchaRequired");
                            if (current.mfaRequired()
                                    && verifiedFactors.stream()
                                            .noneMatch(
                                                    f ->
                                                            f.equals("TOTP")
                                                                    || f.equals("RECOVERY_CODE")))
                                throw failure("secondFactorRequired");
                            if (!maintenance.canLogin(name)) throw unavailable("maintenanceActive");
                            return current;
                        });
        UUID currentRevision = effective.revision();
        SecurityContextHolder.clearContext();
        var details = users.loadUserByUsername(name);
        if (!details.isEnabled()) throw failure("authenticationRequired");
        audit.record(name, true, addresses.resolve(req), req.getHeader("User-Agent"));
        try {
            req.changeSessionId();
            var session = req.getSession();
            session.removeAttribute(PENDING);
            session.setAttribute(
                    SecondFactorSession.REQUIREMENT_VERSION_ATTRIBUTE, currentRevision.toString());
            session.setAttribute(AuthenticationSession.PRIMARY_METHOD, method.name());
            session.setAttribute(
                    AuthenticationSession.VERIFIED_FACTORS, List.copyOf(verifiedFactors));
            session.setAttribute(AuthenticationSession.AUTHENTICATED_AT, Instant.now());
            session.setAttribute(
                    AuthenticationSession.AUTHENTICATION_REVISION, currentRevision.toString());
            session.setAttribute(
                    AuthenticationSession.PRIMARY_CREDENTIAL_REVISION,
                    effective.primaryCredentialRevision().toString());
            session.setAttribute(
                    AuthenticationSession.FACTOR_CREDENTIAL_REVISION,
                    effective.factorCredentialRevision().toString());
            CsrfTokenContract.sessionRepository().saveToken(null, req, res);
            var context = SecurityContextHolder.createEmptyContext();
            if (details
                    instanceof
                    org.springframework.security.core.CredentialsContainer credentialsContainer)
                credentialsContainer.eraseCredentials();
            context.setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            details, null, details.getAuthorities()));
            SecurityContextHolder.setContext(context);
            new HttpSessionSecurityContextRepository().saveContext(context, req, res);
            return new LoginResponse(
                    true,
                    false,
                    null,
                    null,
                    "COMPLETE",
                    currentRevision.toString(),
                    verifiedFactors,
                    null);
        } catch (RuntimeException error) {
            SecurityContextHolder.clearContext();
            var session = req.getSession(false);
            if (session != null) {
                try {
                    session.removeAttribute(
                            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                    session.invalidate();
                } catch (IllegalStateException alreadyInvalidated) {
                    error.addSuppressed(alreadyInvalidated);
                }
            }
            throw error;
        }
    }

    private Challenge lockChallenge(UUID id, HttpServletRequest req, UUID userId, String username) {
        return jdbc.sql(
                        """
                        SELECT c.user_id,c.provider_id,c.requirement_version,c.policy_revision,
                               c.primary_method,c.state,c.attempts,c.expires_at,
                               c.captcha_policy_revision,c.captcha_satisfied
                          FROM platform_login_challenge c
                         WHERE c.id=:id AND c.user_id=:user AND c.session_hash=:session
                           AND c.expires_at>CURRENT_TIMESTAMP
                         FOR UPDATE
                        """)
                .param("id", id)
                .param("user", userId)
                .param("session", sessionHash(req))
                .query(
                        (rs, row) ->
                                new Challenge(
                                        rs.getObject("user_id", UUID.class),
                                        username,
                                        rs.getString("provider_id"),
                                        rs.getObject("requirement_version", UUID.class),
                                        rs.getObject("policy_revision", UUID.class),
                                        PrimaryMethod.valueOf(rs.getString("primary_method")),
                                        rs.getString("state"),
                                        rs.getInt("attempts"),
                                        rs.getTimestamp("expires_at").toInstant(),
                                        rs.getString("captcha_policy_revision"),
                                        rs.getBoolean("captcha_satisfied")))
                .optional()
                .orElse(null);
    }

    private UUID challengeUser(UUID challengeId, HttpServletRequest req) {
        return jdbc.sql(
                        """
                        SELECT user_id FROM platform_login_challenge
                         WHERE id=:id AND session_hash=:session AND expires_at>CURRENT_TIMESTAMP
                        """)
                .param("id", challengeId)
                .param("session", sessionHash(req))
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private String lockUser(UUID userId) {
        return jdbc.sql(
                        """
                        SELECT username FROM platform_user
                         WHERE id=:id AND enabled AND deleted_at IS NULL FOR UPDATE
                        """)
                .param("id", userId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> failure("authenticationRequired"));
    }

    private PasswordCredentialProof.Evidence verifyPasswordProof(
            Authentication authentication, UUID userId) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserDetails details)
                || !(authentication.getDetails()
                        instanceof PasswordCredentialProof.Evidence evidence)
                || !details.getUsername().equals(authentication.getName()))
            throw failure("loginFailed");
        String currentHash =
                jdbc.sql("SELECT password_hash FROM platform_user WHERE id=:id")
                        .param("id", userId)
                        .query(String.class)
                        .optional()
                        .orElseThrow(() -> failure("loginFailed"));
        if (!passwordProofs.matches(evidence.hashFingerprint(), currentHash))
            throw failure("loginFailed");
        return evidence;
    }

    private UUID lockUserByName(String username) {
        return jdbc.sql(
                        """
                        SELECT id FROM platform_user
                         WHERE username=:name AND enabled AND deleted_at IS NULL FOR UPDATE
                        """)
                .param("name", username)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> failure("authenticationRequired"));
    }

    private void deleteChallenge(UUID id) {
        jdbc.sql("DELETE FROM platform_login_challenge WHERE id=:id").param("id", id).update();
    }

    private void incrementAttempts(UUID id) {
        jdbc.sql("UPDATE platform_login_challenge SET attempts=LEAST(attempts+1,5) WHERE id=:id")
                .param("id", id)
                .update();
    }

    private record ProviderResult<T>(T value) {}

    /** Keep plugin calls inside the runtime lease; never retain a plugin bean. */
    private <T> T withProvider(java.util.function.Function<SecondFactorProvider, T> action) {
        var results =
                plugins
                        .extensions(
                                SecondFactorProvider.class,
                                provider ->
                                        provider.id().equals("mfa") && provider.available()
                                                ? new ProviderResult<>(action.apply(provider))
                                                : null)
                        .stream()
                        .filter(Objects::nonNull)
                        .toList();
        if (results.size() != 1) throw unavailable("secondFactorUnavailable");
        return results.getFirst().value();
    }

    private String pendingId(HttpServletRequest req) {
        var s = req.getSession(false);
        if (s == null) return null;
        Object value = s.getAttribute(PENDING);
        return value == null ? null : value instanceof String text ? text : "<invalid>";
    }

    private void deleteChallenge(UUID id, HttpServletRequest req) {
        jdbc.sql("DELETE FROM platform_login_challenge WHERE id=:id AND session_hash=:session")
                .param("id", id)
                .param("session", sessionHash(req))
                .update();
    }

    private String sessionHash(HttpServletRequest req) {
        return FixedWindowBuckets.key("mfa-session", req.getSession().getId()).substring(12);
    }

    private boolean captchaSatisfactionCurrent(
            UUID userId,
            String expectedPolicyRevision,
            boolean satisfied,
            String state,
            HttpServletRequest request) {
        if (expectedPolicyRevision == null) return false;
        CaptchaProofAccess.Gate current =
                captcha.begin(CaptchaProofPort.Action.POST_PRIMARY_AUTH, userId, null, request);
        if (!expectedPolicyRevision.equals(current.requirement().policyRevision())) return false;
        if ("CAPTCHA_REQUIRED".equals(state)) return current.requirement().required() && !satisfied;
        return !current.requirement().required() || satisfied;
    }

    private static LoginResponse pendingResponse(
            UUID id, Instant expires, String state, UUID revision) {
        List<String> factors =
                switch (state) {
                    case "MFA_REQUIRED" -> List.of("TOTP", "RECOVERY_CODE");
                    case "ENROLLMENT_REQUIRED" -> List.of("TOTP");
                    default -> List.of();
                };
        String next =
                switch (state) {
                    case "CAPTCHA_REQUIRED" -> "/login/captcha";
                    case "PASSWORD_CHANGE_REQUIRED" -> "/login/password-change";
                    case "MFA_REQUIRED" -> "/login/second-factor";
                    case "ENROLLMENT_REQUIRED" -> "/login/enroll-authenticator";
                    case "RECOVERY_REQUIRED" -> "/login/recover-authenticator";
                    default -> null;
                };
        return new LoginResponse(
                false,
                !"PASSWORD_CHANGE_REQUIRED".equals(state),
                id,
                expires,
                state,
                revision.toString(),
                factors,
                next);
    }

    private static void unauthorized(String reason) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
    }

    private static ResponseStatusException failure(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }

    static String challengeState(boolean recoveryPending, boolean selfEnabled, boolean configured) {
        if (recoveryPending || selfEnabled && !configured) return "RECOVERY_REQUIRED";
        return configured ? "MFA_REQUIRED" : "ENROLLMENT_REQUIRED";
    }

    private record Requirement(
            String provider, UUID version, boolean selfEnabled, boolean recoveryPending) {}

    private record Pending(
            UUID id,
            UUID userId,
            Instant expiresAt,
            String state,
            PrimaryMethod primaryMethod,
            UUID revision,
            String captchaPolicyRevision,
            boolean captchaSatisfied) {}
}
