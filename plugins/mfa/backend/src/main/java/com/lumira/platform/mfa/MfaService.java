package com.lumira.platform.mfa;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.FixedWindowBuckets;
import com.lumira.platform.common.MailSender;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.identity.api.AccountOwnerPort;
import com.lumira.platform.identity.api.AccountSecurityPort;
import com.lumira.platform.identity.api.AuthorizationContextBoundaryPort;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.identity.api.MfaRecoveryTicketPort;
import com.lumira.platform.identity.api.ReauthenticationPort;
import com.lumira.platform.identity.api.SecondFactorProvider;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** TOTP provider; all identity policy and challenge state remains owned by the host. */
@Service
public class MfaService implements SecondFactorProvider {
    private static final int SETUP_LIFETIME_SECONDS = 300;
    private static final String PROVIDER = "mfa";

    private final MfaStore store;
    private final AccountOwnerPort owners;
    private final AccountSecurityPort security;
    private final EffectiveAuthenticationPolicyPort policies;
    private final AuthenticationPolicyWriteLock policyLock;
    private final ReauthenticationPort reauthentication;
    private final SecretKeys keys;
    private final TransactionTemplate tx;
    private final AuditPort audit;
    private final AdministrationPort administration;
    private final MailSender mail;
    private final MfaRecoveryTicketPort recoveryTickets;
    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuthorizationContextBoundaryPort authorizationBoundary;

    public MfaService(
            MfaStore store,
            AccountOwnerPort owners,
            AccountSecurityPort security,
            EffectiveAuthenticationPolicyPort policies,
            AuthenticationPolicyWriteLock policyLock,
            ReauthenticationPort reauthentication,
            SecretKeys keys,
            TransactionTemplate tx,
            AuditPort audit,
            AdministrationPort administration,
            MailSender mail,
            MfaRecoveryTicketPort recoveryTickets,
            JdbcClient jdbc) {
        this.store = store;
        this.owners = owners;
        this.security = security;
        this.policies = policies;
        this.policyLock = policyLock;
        this.reauthentication = reauthentication;
        this.keys = keys;
        this.tx = tx;
        this.audit = audit;
        this.administration = administration;
        this.mail = mail;
        this.recoveryTickets = recoveryTickets;
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return PROVIDER;
    }

    @Override
    public boolean available() {
        return keys.configured();
    }

    @Override
    public boolean supportsRestrictedEnrollmentAndRecovery() {
        return true;
    }

    @Override
    public AccountStatus status(UUID userId) {
        if (!available()) return null;
        return tx.execute(
                status -> {
                    var authenticator =
                            store.find(userId).filter(MfaStore.Authenticator::confirmed);
                    return new AccountStatus(
                            authenticator.isPresent(), store.recoveryCount(userId));
                });
    }

    public MfaResponse.Status status(String actor) {
        requireAvailable();
        return tx.execute(
                status -> {
                    UUID id = owners.id(actor, false);
                    var row = store.find(id).filter(MfaStore.Authenticator::confirmed);
                    return new MfaResponse.Status(
                            row.isPresent(),
                            row.map(MfaStore.Authenticator::label).orElse(null),
                            row.map(MfaStore.Authenticator::created).orElse(null),
                            store.recoveryCount(id));
                });
    }

    /** Starts a user initiated, action-ticket protected enrollment. */
    public SecondFactorProvider.EnrollmentSetup setup(
            String actor, UUID ticketId, String label, String sessionId) {
        requireAvailable();
        String safeLabel = label(label);
        return tx.execute(
                status -> {
                    policyLock.lockForAuthentication();
                    validateCurrentRequestBoundary();
                    UUID id = owners.id(actor, true);
                    reauthentication.consume(
                            ticketId, id, ReauthenticationPort.Action.MFA_ENROLL, sessionId);
                    return beginEnrollment(
                            id, actor, safeLabel, UUID.randomUUID(), sessionHash(sessionId));
                });
    }

    /** Called only by the unified host's restricted login-enrollment flow. */
    @Override
    public SecondFactorProvider.EnrollmentSetup startEnrollment(
            UUID userId, String username, String label, UUID challengeId, String sessionHash) {
        requireAvailable();
        if (challengeId == null || sessionHash == null || !sessionHash.matches("[A-Fa-f0-9]{64}"))
            throw error(HttpStatus.BAD_REQUEST, "secondFactorEnrollmentUnavailable");
        return beginEnrollment(userId, username, label(label), challengeId, sessionHash);
    }

    /** Begins a staged replacement without touching the currently confirmed factor or its codes. */
    public SecondFactorProvider.EnrollmentSetup replacementSetup(
            String actor, UUID ticketId, String label, String sessionId) {
        requireAvailable();
        String safeLabel = label(label);
        return tx.execute(
                status -> {
                    policyLock.lockForAuthentication();
                    validateCurrentRequestBoundary();
                    UUID id = owners.id(actor, true);
                    reauthentication.consume(
                            ticketId, id, ReauthenticationPort.Action.MFA_REPLACE, sessionId);
                    store.lockUser(id);
                    if (store.findForUpdate(id).filter(MfaStore.Authenticator::confirmed).isEmpty())
                        throw error(HttpStatus.CONFLICT, "mfaNotEnabled");
                    UUID authenticationRevision = policies.resolve(id).revision();

                    byte[] secret = new byte[20];
                    random.nextBytes(secret);
                    UUID challengeId = UUID.randomUUID();
                    Instant expires = Instant.now().plusSeconds(SETUP_LIFETIME_SECONDS);
                    try {
                        UUID factorRevision =
                                jdbc.sql(
                                                "SELECT factor_credential_revision FROM platform_user WHERE id=:id")
                                        .param("id", id)
                                        .query(UUID.class)
                                        .single();
                        store.replacementSetup(
                                id,
                                safeLabel,
                                keys.require().encrypt(context(id) + ":replacement", secret),
                                challengeId,
                                sessionHash(sessionId),
                                factorRevision,
                                authenticationRevision,
                                expires);
                        String encoded = Totp.base32(secret);
                        String uri =
                                "otpauth://totp/"
                                        + url("Lumira:" + actor)
                                        + "?secret="
                                        + encoded
                                        + "&issuer=Lumira&algorithm=SHA1&digits=6&period=30";
                        return new SecondFactorProvider.EnrollmentSetup(
                                challengeId, encoded, uri, expires);
                    } finally {
                        Arrays.fill(secret, (byte) 0);
                    }
                });
    }

    /**
     * A failed OTP commits its attempt counter; successful replacement is atomic with code
     * rotation.
     */
    public MfaResponse.Recovery replacementConfirm(
            String actor, UUID challengeId, String code, String sessionId) {
        requireAvailable();
        MfaResponse.Recovery result =
                tx.execute(
                        status -> {
                            policyLock.lockForAuthentication();
                            validateCurrentRequestBoundary();
                            UUID id = owners.id(actor, true);
                            store.lockUser(id);
                            MfaStore.Replacement replacement =
                                    store.replacementForUpdate(id)
                                            .filter(
                                                    value ->
                                                            challengeId != null
                                                                    && challengeId.equals(
                                                                            value.challengeId()))
                                            .orElse(null);
                            if (replacement == null
                                    || !replacement.expires().isAfter(Instant.now())
                                    || replacement.attempts() >= 5
                                    || !constantTimeEquals(
                                            sessionHash(sessionId), replacement.sessionHash()))
                                return null;
                            UUID currentFactorRevision =
                                    jdbc.sql(
                                                    "SELECT factor_credential_revision FROM platform_user WHERE id=:id")
                                            .param("id", id)
                                            .query(UUID.class)
                                            .single();
                            if (!replacement
                                    .factorCredentialRevision()
                                    .equals(currentFactorRevision)) {
                                store.deleteReplacement(id, challengeId);
                                return null;
                            }
                            if (!replacement
                                    .authenticationRevision()
                                    .equals(policies.resolve(id).revision())) {
                                store.deleteReplacement(id, challengeId);
                                return null;
                            }
                            ReplacementVerification verification =
                                    verifyReplacementTotp(id, replacement, code);
                            if (verification.step() < 0) {
                                store.incrementReplacementAttempts(id, challengeId);
                                return null;
                            }
                            if (store.activateReplacement(
                                            id,
                                            challengeId,
                                            verification.step(),
                                            verification.activeEnvelope())
                                    != 1) throw error(HttpStatus.GONE, "mfaReplacementExpired");
                            var recovery = new MfaResponse.Recovery(recoveryCodes(id));
                            security.bumpFactorCredentialRevision(id);
                            security.revokeSessions(id, null);
                            audit.append(actor, "mfa.replaced", id);
                            return recovery;
                        });
        if (result == null) throw error(HttpStatus.BAD_REQUEST, "secondFactorInvalid");
        return result;
    }

    public void replacementCancel(String actor, UUID challengeId, String sessionId) {
        requireAvailable();
        tx.executeWithoutResult(
                status -> {
                    policyLock.lockForAuthentication();
                    validateCurrentRequestBoundary();
                    UUID id = owners.id(actor, true);
                    store.lockUser(id);
                    if (challengeId == null
                            || store.cancelReplacement(id, challengeId, sessionHash(sessionId))
                                    != 1) throw error(HttpStatus.GONE, "mfaReplacementExpired");
                });
    }

    private SecondFactorProvider.EnrollmentSetup beginEnrollment(
            UUID userId, String username, String label, UUID challengeId, String sessionHash) {
        store.lockUser(userId);
        var existing = store.findForUpdate(userId);
        if (existing.filter(MfaStore.Authenticator::confirmed).isPresent())
            throw error(HttpStatus.CONFLICT, "mfaAlreadyEnabled");

        byte[] secret = new byte[20];
        random.nextBytes(secret);
        Instant expires = Instant.now().plusSeconds(SETUP_LIFETIME_SECONDS);
        try {
            store.setup(
                    userId,
                    label,
                    keys.require().encrypt(context(userId), secret),
                    expires,
                    challengeId,
                    sessionHash);
            String encoded = Totp.base32(secret);
            String uri =
                    "otpauth://totp/"
                            + url("Lumira:" + username)
                            + "?secret="
                            + encoded
                            + "&issuer=Lumira&algorithm=SHA1&digits=6&period=30";
            return new SecondFactorProvider.EnrollmentSetup(challengeId, encoded, uri, expires);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /**
     * Completes self-service enrollment; response contains the only plaintext recovery-code copy.
     */
    public MfaResponse.Recovery confirmSetup(
            String actor, UUID challengeId, String code, String sessionId) {
        requireAvailable();
        MfaResponse.Recovery result =
                tx.execute(
                        status -> {
                            policyLock.lockForAuthentication();
                            validateCurrentRequestBoundary();
                            UUID id = owners.id(actor, true);
                            var confirmation =
                                    confirm(id, challengeId, sessionHash(sessionId), code, true);
                            if (confirmation == null) return null;
                            security.bumpFactorCredentialRevision(id);
                            security.setSecondFactorSources(id, PROVIDER, true, false);
                            audit.append(actor, "mfa.enabled", id);
                            return new MfaResponse.Recovery(confirmation.recoveryCodes());
                        });
        if (result == null) throw error(HttpStatus.BAD_REQUEST, "secondFactorInvalid");
        return result;
    }

    @Override
    public EnrollmentConfirmation confirmEnrollment(
            UUID userId, UUID challengeId, String sessionHash, String code, boolean selfEnabled) {
        requireAvailable();
        validateCurrentRequestBoundary();
        var confirmation = confirm(userId, challengeId, sessionHash, code, selfEnabled);
        if (confirmation == null) return null;
        if (selfEnabled) {
            security.bumpFactorCredentialRevision(userId);
            security.setSecondFactorSources(userId, PROVIDER, true, false);
        }
        // The host's restricted-enrollment completion owns the single factor epoch bump and
        // clears RECOVERY_PENDING while preserving SELF_ENABLED in the same transaction.
        security.revokeSessions(userId, null);
        return confirmation;
    }

    private EnrollmentConfirmation confirm(
            UUID userId, UUID challengeId, String sessionHash, String code, boolean selfEnabled) {
        store.lockUser(userId);
        var authenticator =
                store.findForUpdate(userId)
                        .orElseThrow(() -> error(HttpStatus.GONE, "mfaSetupExpired"));
        if (authenticator.confirmed()
                || authenticator.expires() == null
                || !authenticator.expires().isAfter(Instant.now())
                || authenticator.setupAttempts() >= 5
                || challengeId == null
                || !challengeId.equals(authenticator.setupChallengeId())
                || sessionHash == null
                || !constantTimeEquals(sessionHash, authenticator.setupSessionHash()))
            throw error(
                    HttpStatus.GONE,
                    authenticator.setupAttempts() >= 5 ? "secondFactorLocked" : "mfaSetupExpired");
        long step = checkTotp(userId, authenticator, code);
        if (step < 0) {
            store.incrementSetupAttempts(userId, challengeId);
            return null;
        }
        store.activate(userId, challengeId, step);
        List<String> codes = recoveryCodes(userId);
        // Required enrollment is evidence for this login only; it does not persist SELF_ENABLED.
        return new EnrollmentConfirmation(codes);
    }

    @Override
    public boolean verify(UUID id, String code) {
        return verifyEvidence(id, code) != null;
    }

    @Override
    public String verifyEvidence(UUID id, String code) {
        return verifyEvidence(id, code, null);
    }

    @Override
    public String verifyEvidence(UUID id, String code, String retainedSessionId) {
        if (!available()) return null;
        return tx.execute(
                status -> {
                    validateCurrentRequestBoundary();
                    store.lockUser(id);
                    var authenticator = store.findForUpdate(id);
                    if (authenticator.isEmpty() || !authenticator.get().confirmed()) return null;
                    long step = checkTotp(id, authenticator.get(), code);
                    if (step >= 0) {
                        store.step(id, step);
                        return "TOTP";
                    }
                    if (code == null
                            || !code.matches(
                                    "[A-Fa-f0-9]{8}-?[A-Fa-f0-9]{8}-?[A-Fa-f0-9]{8}-?[A-Fa-f0-9]{8}"))
                        return null;
                    byte[] value = normalized(code);
                    try {
                        for (String digest : store.recoveryDigests(id)) {
                            if (keys.require()
                                    .matchesAuthentication(recoveryContext(id), value, digest)) {
                                if (!store.consumeRecovery(id, digest)) return null;
                                security.bumpFactorCredentialRevision(id);
                                security.revokeSessions(id, retainedSessionId);
                                return "RECOVERY_CODE";
                            }
                        }
                        return null;
                    } finally {
                        Arrays.fill(value, (byte) 0);
                    }
                });
    }

    public MfaResponse.Recovery regenerate(String actor, UUID ticketId, String sessionId) {
        requireAvailable();
        return tx.execute(
                status -> {
                    policyLock.lockForAuthentication();
                    validateCurrentRequestBoundary();
                    UUID id = owners.id(actor, true);
                    reauthentication.consume(
                            ticketId,
                            id,
                            ReauthenticationPort.Action.MFA_RECOVERY_REGENERATE,
                            sessionId);
                    if (store.findForUpdate(id).filter(MfaStore.Authenticator::confirmed).isEmpty())
                        throw error(HttpStatus.CONFLICT, "mfaNotEnabled");
                    audit.append(actor, "mfa.recoveryRegenerated", id);
                    var recovery = new MfaResponse.Recovery(recoveryCodes(id));
                    security.bumpFactorCredentialRevision(id);
                    security.revokeSessions(id, null);
                    return recovery;
                });
    }

    public void rename(String actor, UUID ticketId, String label, String sessionId) {
        requireAvailable();
        String safeLabel = label(label);
        tx.executeWithoutResult(
                status -> {
                    policyLock.lockForAuthentication();
                    validateCurrentRequestBoundary();
                    UUID id = owners.id(actor, true);
                    reauthentication.consume(
                            ticketId, id, ReauthenticationPort.Action.MFA_RENAME, sessionId);
                    if (store.findForUpdate(id).filter(MfaStore.Authenticator::confirmed).isEmpty())
                        throw error(HttpStatus.CONFLICT, "mfaNotEnabled");
                    store.rename(id, safeLabel);
                    audit.append(actor, "mfa.renamed", id);
                });
    }

    public void disable(String actor, UUID ticketId, String sessionId) {
        requireAvailable();
        tx.executeWithoutResult(
                status -> {
                    policyLock.lockForAuthentication();
                    validateCurrentRequestBoundary();
                    UUID id = owners.id(actor, true);
                    reauthentication.consume(
                            ticketId, id, ReauthenticationPort.Action.MFA_DISABLE, sessionId);
                    if (policies.requiredByPolicyExceptSelf(id))
                        throw error(HttpStatus.CONFLICT, "mfaRequiredByPolicy");
                    if (store.findForUpdate(id).filter(MfaStore.Authenticator::confirmed).isEmpty())
                        throw error(HttpStatus.CONFLICT, "mfaNotEnabled");
                    remove(actor, id, sessionId, "mfa.disabled");
                });
    }

    public PageResult<MfaResponse.Account> list(PageRequest page) {
        return store.list(page);
    }

    /** Bounded maintenance only; no request path scans or deletes all expired MFA state. */
    public int purgeExpired(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 2_000));
        return tx.execute(
                status -> {
                    int pluginRows = store.purgeExpired(limit);
                    int recoveryRows =
                            jdbc.sql(
                                            """
                            WITH expired AS (
                              SELECT id FROM platform_mfa_recovery_ticket
                               WHERE expires_at<=CURRENT_TIMESTAMP OR consumed_at IS NOT NULL OR revoked_at IS NOT NULL
                               ORDER BY expires_at,id FOR UPDATE SKIP LOCKED LIMIT :limit
                            ) DELETE FROM platform_mfa_recovery_ticket t USING expired e WHERE t.id=e.id
                            """)
                                    .param("limit", limit)
                                    .update();
                    return pluginRows + recoveryRows;
                });
    }

    /**
     * Reset requires a fully authorized operator and an in-transaction encrypted delivery enqueue.
     */
    public void reset(String actor, UUID target, UUID ticketId, String sessionId, String reason) {
        if (target == null
                || reason == null
                || reason.strip().length() < 5
                || reason.length() > 500)
            throw error(HttpStatus.BAD_REQUEST, "mfaResetReasonRequired");
        requireAvailable();
        try {
            mail.ensureAvailable();
        } catch (RuntimeException unavailable) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "mfaRecoveryDeliveryUnavailable");
        }
        tx.executeWithoutResult(
                status -> {
                    policyLock.lockForAuthentication();
                    administration.require(actor, "platform:mfa:manage");
                    administration.requireGlobal(actor, "platform:mfa:manage");
                    administration.requireUser(actor, target, "platform:users:write");
                    UUID actorId = owners.id(actor, false);
                    if (target.equals(actorId))
                        throw error(HttpStatus.CONFLICT, "mfaSelfResetForbidden");
                    List.of(actorId, target).stream().distinct().sorted().forEach(store::lockUser);
                    reauthentication.consume(
                            ticketId,
                            actorId,
                            ReauthenticationPort.Action.MFA_ADMIN_RESET,
                            sessionId);
                    if (store.findForUpdate(target)
                            .filter(MfaStore.Authenticator::confirmed)
                            .isEmpty()) throw error(HttpStatus.CONFLICT, "mfaNotEnabled");
                    String email =
                            store.verifiedEmail(target)
                                    .orElseThrow(
                                            () ->
                                                    error(
                                                            HttpStatus.SERVICE_UNAVAILABLE,
                                                            "mfaRecoveryDeliveryUnavailable"));
                    try {
                        mail.validateRecipient(email);
                    } catch (RuntimeException invalid) {
                        throw error(
                                HttpStatus.SERVICE_UNAVAILABLE, "mfaRecoveryDeliveryUnavailable");
                    }
                    store.delete(target);
                    store.deleteReplacement(target);
                    recoveryTickets.revoke(target);
                    security.markRecoveryPending(target, PROVIDER);
                    store.revokeAuthenticationChallenges(target);
                    security.revokeSessions(target, null);
                    var issued = recoveryTickets.issue(target);
                    boolean queued =
                            mail.enqueue(
                                    UUID.randomUUID(),
                                    email,
                                    "Lumira MFA recovery ticket",
                                    "Your authenticator was reset by an authorized administrator. "
                                            + "Use this one-time recovery ticket within 15 minutes in the sign-in recovery flow.\n\n"
                                            + issued.value()
                                            + "\n\nIf you did not expect this, contact your administrator immediately. / 如果这不是你预期的操作，请立即联系管理员。",
                                    900);
                    if (!queued)
                        throw error(
                                HttpStatus.SERVICE_UNAVAILABLE, "mfaRecoveryDeliveryUnavailable");
                    store.recordReset(actor, target, reason);
                    audit.append(actor, "mfa.adminReset", target);
                });
    }

    /** Only a current RECOVERY_REQUIRED challenge can reach this provider operation. */
    @Override
    public SecondFactorProvider.RecoveryRequest requestRecovery(UUID id) {
        requireAvailable();
        try {
            mail.ensureAvailable();
        } catch (RuntimeException unavailable) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "mfaRecoveryDeliveryUnavailable");
        }
        return tx.execute(
                status -> {
                    policyLock.lockForAuthentication();
                    store.lockUser(id);
                    boolean pending =
                            jdbc.sql(
                                            """
                            SELECT recovery_pending FROM platform_second_factor_requirement
                             WHERE user_id=:id AND provider_id='mfa' FOR UPDATE
                            """)
                                    .param("id", id)
                                    .query(Boolean.class)
                                    .optional()
                                    .orElse(false);
                    if (!pending) throw error(HttpStatus.CONFLICT, "mfaRecoveryNotPending");
                    if (!store.allowRecoveryRequest(id, 30, 10))
                        throw error(HttpStatus.TOO_MANY_REQUESTS, "mfaRecoveryLimited");
                    String email =
                            store.verifiedEmail(id)
                                    .orElseThrow(
                                            () ->
                                                    error(
                                                            HttpStatus.SERVICE_UNAVAILABLE,
                                                            "mfaRecoveryDeliveryUnavailable"));
                    try {
                        mail.validateRecipient(email);
                    } catch (RuntimeException invalid) {
                        throw error(
                                HttpStatus.SERVICE_UNAVAILABLE, "mfaRecoveryDeliveryUnavailable");
                    }
                    var issued = recoveryTickets.issue(id);
                    boolean queued =
                            mail.enqueue(
                                    UUID.randomUUID(),
                                    email,
                                    "Lumira MFA recovery ticket",
                                    "Use this one-time recovery ticket within 15 minutes in the sign-in recovery flow.\n\n"
                                            + issued.value()
                                            + "\n\nThe ticket can only unlock restricted authenticator enrollment. / 此票据只允许进入受限验证器重新绑定流程。",
                                    900);
                    if (!queued)
                        throw error(
                                HttpStatus.SERVICE_UNAVAILABLE, "mfaRecoveryDeliveryUnavailable");
                    return new SecondFactorProvider.RecoveryRequest(issued.expiresAt());
                });
    }

    private void remove(String actor, UUID id, String retainedSession, String action) {
        store.delete(id);
        security.clearSecondFactor(id, PROVIDER);
        security.revokeSessions(id, retainedSession);
        audit.append(actor, action, id);
    }

    private long checkTotp(UUID id, MfaStore.Authenticator authenticator, String code) {
        byte[] secret = keys.require().decrypt(context(id), authenticator.envelope());
        try {
            return Totp.verify(
                    secret, code, Instant.now().getEpochSecond(), authenticator.lastStep());
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private ReplacementVerification verifyReplacementTotp(
            UUID id, MfaStore.Replacement replacement, String code) {
        byte[] secret =
                keys.require().decrypt(context(id) + ":replacement", replacement.envelope());
        try {
            long step =
                    Totp.verify(
                            secret, code, Instant.now().getEpochSecond(), replacement.lastStep());
            if (step < 0) return new ReplacementVerification(-1, null);
            String activeEnvelope = keys.require().encrypt(context(id), secret);
            return new ReplacementVerification(step, activeEnvelope);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private record ReplacementVerification(long step, String activeEnvelope) {}

    private List<String> recoveryCodes(UUID id) {
        store.clearRecovery(id);
        var codes = new ArrayList<String>(10);
        for (int i = 0; i < 10; i++) {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            String code = HexFormat.of().withUpperCase().formatHex(bytes);
            Arrays.fill(bytes, (byte) 0);
            byte[] normalized = normalized(code);
            try {
                store.recovery(id, keys.require().authenticate(recoveryContext(id), normalized));
            } finally {
                Arrays.fill(normalized, (byte) 0);
            }
            codes.add(
                    String.join(
                            "-",
                            code.substring(0, 8),
                            code.substring(8, 16),
                            code.substring(16, 24),
                            code.substring(24, 32)));
        }
        return List.copyOf(codes);
    }

    private static byte[] normalized(String code) {
        return code.replace("-", "").toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
    }

    private static String label(String name) {
        if (name == null || name.strip().isEmpty() || name.length() > 128)
            throw error(HttpStatus.BAD_REQUEST, "mfaLabelRequired");
        return name.strip();
    }

    private void requireAvailable() {
        if (!available()) throw error(HttpStatus.SERVICE_UNAVAILABLE, "secondFactorUnavailable");
    }

    private void validateCurrentRequestBoundary() {
        if (authorizationBoundary != null) authorizationBoundary.lockAndValidateCurrentRequest();
    }

    private static String context(UUID id) {
        return "mfa:" + id + ":secret";
    }

    private static String recoveryContext(UUID id) {
        return "mfa:" + id + ":recovery";
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String sessionHash(String sessionId) {
        if (sessionId == null || sessionId.isBlank())
            throw error(HttpStatus.UNAUTHORIZED, "authenticationRequired");
        return FixedWindowBuckets.key("mfa-session", sessionId).substring("mfa-session:".length());
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (right == null) return false;
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static ResponseStatusException error(HttpStatus status, String key) {
        return new ResponseStatusException(status, key);
    }
}
