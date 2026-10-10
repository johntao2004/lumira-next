package com.lumira.platform.identity;

import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.FixedWindowBuckets;
import com.lumira.platform.common.MailSender;
import com.lumira.platform.common.PluginExtensionsPort;
import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.common.SmsSender;
import com.lumira.platform.identity.api.*;
import com.lumira.platform.identity.api.AuthenticationMethodAvailabilityPort;
import com.lumira.platform.identity.api.AuthenticationSession;
import com.lumira.platform.identity.api.SecondFactorSession;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReauthenticationService implements ReauthenticationPort {
    public record Start(
            UUID challengeId, Instant expiresAt, List<String> methods, boolean factorRequired) {}

    public record CodeChallenge(
            UUID codeChallengeId, OffsetDateTime expiresAt, OffsetDateTime resendAt) {}

    public record PasskeyContext(
            UUID userId, UUID authenticationRevision, UUID primaryCredentialRevision) {}

    public record Ticket(UUID ticketId, Instant expiresAt, String action) {}

    private record Challenge(
            UUID id, UUID userId, String action, UUID revision, int attempts, Instant expiresAt) {}

    private record CodeRow(
            UUID id,
            UUID reauthenticationChallengeId,
            UUID userId,
            String method,
            String contactDigest,
            long contactVersion,
            UUID primaryCredentialRevision,
            UUID factorCredentialRevision,
            String codeDigest,
            String state,
            int attempts,
            OffsetDateTime expiresAt) {}

    private record Completion(
            String failure,
            Ticket ticket,
            UUID authenticationRevision,
            UUID factorCredentialRevision,
            List<String> verifiedFactors) {
        Completion(String failure, Ticket ticket) {
            this(failure, ticket, null, null, List.of());
        }
    }

    private record FactorResult(boolean available, boolean configured, String factor) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuthenticationPolicyWriteLock policyLock;
    private final AccountOwnerPort owners;
    private final EffectiveAuthenticationPolicyPort policies;
    private final PluginExtensionsPort plugins;
    private final PasswordEncoder passwords;
    private final LoginCodeAccountStore contacts;
    private final PublicAuthCrypto publicAuthCrypto;
    private final AuthenticationMethodAvailabilityPort methodAvailability;
    private final MailSender mail;
    private final SmsSender sms;
    private final FixedWindowBuckets buckets;
    private final ClientAddressResolver addresses;
    private final SecureRandom random = new SecureRandom();

    public ReauthenticationService(
            JdbcClient jdbc,
            TransactionTemplate tx,
            AuthenticationPolicyWriteLock policyLock,
            AccountOwnerPort owners,
            EffectiveAuthenticationPolicyPort policies,
            PluginExtensionsPort plugins,
            PasswordEncoder passwords,
            LoginCodeAccountStore contacts,
            PublicAuthCrypto publicAuthCrypto,
            AuthenticationMethodAvailabilityPort methodAvailability,
            MailSender mail,
            SmsSender sms,
            FixedWindowBuckets buckets,
            ClientAddressResolver addresses) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.policyLock = policyLock;
        this.owners = owners;
        this.policies = policies;
        this.plugins = plugins;
        this.passwords = passwords;
        this.contacts = contacts;
        this.publicAuthCrypto = publicAuthCrypto;
        this.methodAvailability = methodAvailability;
        this.mail = mail;
        this.sms = sms;
        this.buckets = buckets;
        this.addresses = addresses;
    }

    public Start start(
            String actor, ReauthenticationPort.Action action, HttpServletRequest request) {
        if (action == null) throw error(HttpStatus.BAD_REQUEST, "reauthenticationActionInvalid");
        String sessionId = sessionId(request);
        UUID actorId = owners.id(actor, false);
        String sessionBinding = sessionHash(sessionId);
        if (!buckets.consume(FixedWindowBuckets.key("reauthsrc", addresses.resolve(request)), 40)
                || !buckets.consume(FixedWindowBuckets.key("reauthsess", sessionId), 12)
                || !buckets.consume(FixedWindowBuckets.key("reauthacct", actorId.toString()), 20))
            throw error(HttpStatus.TOO_MANY_REQUESTS, "reauthenticationStartLimited");
        return tx.execute(
                status -> {
                    policyLock.lockForAuthentication();
                    lockUser(actorId);
                    UUID userId = actorId;
                    var effective = policies.resolve(userId);
                    List<String> methods = supportedMethods(effective, userId);
                    if (methods.isEmpty())
                        throw error(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "reauthenticationMethodUnavailable");
                    if (effective.mfaRequired() && !policies.secondFactorAvailable())
                        throw error(HttpStatus.SERVICE_UNAVAILABLE, "secondFactorUnavailable");
                    UUID revision = effective.revision();
                    UUID challengeId = UUID.randomUUID();
                    Instant expires = Instant.now().plusSeconds(300);
                    jdbc.sql(
                                    """
                            UPDATE platform_reauthentication_challenge
                               SET state='SUPERSEDED'
                             WHERE user_id=:user AND session_hash=:session AND state='PENDING'
                            """)
                            .param("user", userId)
                            .param("session", sessionBinding)
                            .update();
                    jdbc.sql(
                                    """
                            UPDATE platform_reauthentication_code_challenge
                               SET state='SUPERSEDED'
                             WHERE state='PENDING' AND reauthentication_challenge_id IN (
                                 SELECT id FROM platform_reauthentication_challenge
                                  WHERE user_id=:user AND session_hash=:session AND state='SUPERSEDED'
                             )
                            """)
                            .param("user", userId)
                            .param("session", sessionBinding)
                            .update();
                    jdbc.sql(
                                    """
                            INSERT INTO platform_reauthentication_challenge
                                (id,user_id,session_hash,action,authentication_revision,expires_at)
                            VALUES (:id,:user,:session,:action,:revision,:expires)
                            """)
                            .param("id", challengeId)
                            .param("user", userId)
                            .param("session", sessionBinding)
                            .param("action", action.name())
                            .param("revision", revision)
                            .param("expires", java.sql.Timestamp.from(expires))
                            .update();
                    return new Start(challengeId, expires, methods, effective.mfaRequired());
                });
    }

    /** Sends an action-purpose OTP to the account's currently verified contact only. */
    public CodeChallenge requestCode(
            String actor, UUID challengeId, String method, HttpServletRequest request) {
        PrimaryMethod primaryMethod = parseReauthenticationMethod(method);
        String channel = channel(primaryMethod);
        boolean providerReady =
                primaryMethod == PrimaryMethod.EMAIL_OTP ? mail.ready() : sms.ready();
        if (!providerReady || !methodAvailability.available(primaryMethod))
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "reauthenticationMethodUnavailable");

        String sessionId = sessionId(request);
        String sessionBinding = sessionHash(sessionId);
        UUID actorId = owners.id(actor, false);
        Challenge preflight = readChallenge(challengeId, sessionBinding);
        if (preflight == null || !preflight.userId().equals(actorId))
            throw error(HttpStatus.GONE, "reauthenticationExpired");
        var preflightPolicy = policies.resolve(actorId);
        if (!preflightPolicy.revision().equals(preflight.revision())
                || !supportedMethods(preflightPolicy, actorId).contains(primaryMethod.name()))
            throw error(HttpStatus.GONE, "reauthenticationExpired");

        String sourceDigest = publicAuthCrypto.sourceDigest(addresses.resolve(request));
        String sessionDigest = publicAuthCrypto.sessionDigest(sessionId);
        if (!buckets.consume(FixedWindowBuckets.key("reauth-source", sourceDigest), 20)
                || !buckets.consume(FixedWindowBuckets.key("reauth-session", sessionDigest), 5)
                || !buckets.consume(
                        FixedWindowBuckets.key("reauth-contact", actorId + ":" + channel), 5))
            throw error(HttpStatus.TOO_MANY_REQUESTS, "reauthenticationCodeLimited");

        return tx.execute(
                status -> {
                    policyLock.lockForAuthentication();
                    lockUser(actorId);
                    Challenge challenge = lockChallenge(challengeId, actorId, sessionBinding);
                    if (challenge == null || !challenge.action().equals(preflight.action()))
                        throw error(HttpStatus.GONE, "reauthenticationExpired");
                    var effective = policies.resolve(actorId);
                    if (!effective.revision().equals(challenge.revision())
                            || !supportedMethods(effective, actorId).contains(primaryMethod.name()))
                        throw error(HttpStatus.GONE, "reauthenticationExpired");
                    if (challenge.attempts() >= 5)
                        throw error(HttpStatus.TOO_MANY_REQUESTS, "reauthenticationLocked");

                    var contact = contacts.lockEnabledAccountContact(actorId, channel).orElse(null);
                    if (contact == null
                            || !contact.verified()
                            || !contact.primaryCredentialRevision()
                                    .equals(effective.primaryCredentialRevision()))
                        throw error(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "reauthenticationMethodUnavailable");

                    long sent =
                            jdbc.sql(
                                            """
                            SELECT count(*) FROM platform_reauthentication_code_challenge
                             WHERE reauthentication_challenge_id=:challenge
                               AND created_at>CURRENT_TIMESTAMP-INTERVAL '5 minutes'
                            """)
                                    .param("challenge", challengeId)
                                    .query(Long.class)
                                    .single();
                    OffsetDateTime lastSent =
                            jdbc.sql(
                                            """
                            SELECT max(created_at) FROM platform_reauthentication_code_challenge
                             WHERE reauthentication_challenge_id=:challenge
                            """)
                                    .param("challenge", challengeId)
                                    .query(OffsetDateTime.class)
                                    .optional()
                                    .orElse(null);
                    OffsetDateTime now = OffsetDateTime.now();
                    if (sent >= 3 || lastSent != null && lastSent.isAfter(now.minusSeconds(60)))
                        throw error(HttpStatus.TOO_MANY_REQUESTS, "reauthenticationCodeLimited");

                    String normalizedContact = normalizeContact(channel, contact.contact());
                    String purposeSession = publicAuthCrypto.sessionDigest(sessionId);
                    String contactDigest =
                            publicAuthCrypto.contactDigest(
                                    PublicAuthPurpose.REAUTHENTICATION, channel, normalizedContact);
                    String code = publicAuthCrypto.issueCode();
                    UUID codeChallengeId = UUID.randomUUID();
                    String codeDigest =
                            publicAuthCrypto.codeDigest(
                                    PublicAuthPurpose.REAUTHENTICATION,
                                    codeChallengeId,
                                    purposeSession,
                                    contactDigest,
                                    code);
                    OffsetDateTime expires = now.plusMinutes(5);
                    jdbc.sql(
                                    """
                            UPDATE platform_reauthentication_code_challenge
                               SET state='SUPERSEDED'
                             WHERE reauthentication_challenge_id=:challenge AND state='PENDING'
                            """)
                            .param("challenge", challengeId)
                            .update();
                    jdbc.sql(
                                    """
                            INSERT INTO platform_reauthentication_code_challenge
                                (id,reauthentication_challenge_id,user_id,session_hash,method,
                                 contact_digest,contact_version,primary_credential_revision,
                                 factor_credential_revision,code_digest,state,expires_at)
                            VALUES (:id,:challenge,:user,:session,:method,:contact,:contactVersion,
                                    :primary,:factor,:digest,'PENDING',:expires)
                            """)
                            .param("id", codeChallengeId)
                            .param("challenge", challengeId)
                            .param("user", actorId)
                            .param("session", sessionBinding)
                            .param("method", primaryMethod.name())
                            .param("contact", contactDigest)
                            .param("contactVersion", contact.contactVersion())
                            .param("primary", effective.primaryCredentialRevision())
                            .param("factor", effective.factorCredentialRevision())
                            .param("digest", codeDigest)
                            .param("expires", expires)
                            .update();
                    boolean queued;
                    if (primaryMethod == PrimaryMethod.EMAIL_OTP) {
                        mail.validateRecipient(normalizedContact);
                        queued =
                                mail.enqueue(
                                        UUID.randomUUID(),
                                        normalizedContact,
                                        "Lumira sensitive action verification",
                                        "Your one-time code for a sensitive account action is "
                                                + code
                                                + ". It expires in five minutes. Do not share this code.",
                                        300);
                    } else {
                        queued =
                                sms.enqueueVerification(
                                        UUID.randomUUID(), normalizedContact, code, 300);
                    }
                    if (!queued)
                        throw error(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "reauthenticationChannelUnavailable");
                    return new CodeChallenge(codeChallengeId, expires, now.plusSeconds(60));
                });
    }

    /** A purpose-bound OTP cannot be substituted with login, registration, or recovery proofs. */
    public Ticket completeCode(
            String actor,
            UUID challengeId,
            UUID codeChallengeId,
            String method,
            String code,
            String factorCode,
            HttpServletRequest request) {
        PrimaryMethod primaryMethod = parseReauthenticationMethod(method);
        if (challengeId == null
                || codeChallengeId == null
                || code == null
                || !code.matches("\\d{6}")
                || factorCode != null && factorCode.length() > 64)
            throw error(HttpStatus.BAD_REQUEST, "reauthenticationInvalid");
        String sessionId = sessionId(request);
        String sessionBinding = sessionHash(sessionId);
        String purposeSession = publicAuthCrypto.sessionDigest(sessionId);
        UUID actorId = owners.id(actor, false);
        String sourceDigest = publicAuthCrypto.sourceDigest(addresses.resolve(request));
        if (!buckets.consume(FixedWindowBuckets.key("reauthv-source", sourceDigest), 30)
                || !buckets.consume(FixedWindowBuckets.key("reauthv-session", purposeSession), 10)
                || !buckets.consume(
                        FixedWindowBuckets.key("reauthv", challengeId + ":" + codeChallengeId), 5))
            throw error(HttpStatus.TOO_MANY_REQUESTS, "reauthenticationCodeLimited");

        Completion completion =
                tx.execute(
                        status -> {
                            policyLock.lockForAuthentication();
                            lockUser(actorId);
                            Challenge parent = lockChallenge(challengeId, actorId, sessionBinding);
                            if (parent == null)
                                return new Completion("reauthenticationExpired", null);
                            var effective = policies.resolve(actorId);
                            if (!effective.revision().equals(parent.revision())
                                    || !supportedMethods(effective, actorId)
                                            .contains(primaryMethod.name()))
                                return new Completion("reauthenticationExpired", null);
                            if (parent.attempts() >= 5)
                                return new Completion("reauthenticationLocked", null);

                            CodeRow proof =
                                    lockCodeChallenge(
                                            codeChallengeId, challengeId, actorId, sessionBinding);
                            if (proof == null
                                    || !proof.method().equals(primaryMethod.name())
                                    || !proof.expiresAt().isAfter(OffsetDateTime.now())
                                    || !"PENDING".equals(proof.state())
                                    || proof.attempts() >= 5)
                                return new Completion("reauthenticationExpired", null);
                            String channel = channel(primaryMethod);
                            var currentContact =
                                    contacts.lockEnabledAccountContact(actorId, channel)
                                            .orElse(null);
                            String normalizedContact =
                                    currentContact == null || !currentContact.verified()
                                            ? null
                                            : normalizeContact(channel, currentContact.contact());
                            String currentDigest =
                                    normalizedContact == null
                                            ? null
                                            : publicAuthCrypto.contactDigest(
                                                    PublicAuthPurpose.REAUTHENTICATION,
                                                    channel,
                                                    normalizedContact);
                            boolean currentBinding =
                                    currentContact != null
                                            && Objects.equals(
                                                    proof.contactVersion(),
                                                    currentContact.contactVersion())
                                            && Objects.equals(
                                                    proof.primaryCredentialRevision(),
                                                    effective.primaryCredentialRevision())
                                            && Objects.equals(
                                                    proof.factorCredentialRevision(),
                                                    effective.factorCredentialRevision())
                                            && Objects.equals(proof.contactDigest(), currentDigest)
                                            && Objects.equals(
                                                    proof.primaryCredentialRevision(),
                                                    currentContact.primaryCredentialRevision());
                            boolean validCode =
                                    currentBinding
                                            && publicAuthCrypto.matchesCode(
                                                    PublicAuthPurpose.REAUTHENTICATION,
                                                    codeChallengeId,
                                                    purposeSession,
                                                    proof.contactDigest(),
                                                    code,
                                                    proof.codeDigest());
                            if (!validCode) {
                                incrementCodeAttempts(codeChallengeId);
                                return failedAttempt(parent, "reauthenticationInvalid");
                            }

                            Completion result =
                                    completeWithFactor(
                                            parent,
                                            effective,
                                            factorCode,
                                            sessionId,
                                            sessionBinding);
                            if (result.failure() == null) consumeCodeChallenge(codeChallengeId);
                            return result;
                        });
        if (completion != null && completion.failure() == null)
            refreshSessionAfterFactorRotation(completion, request);
        return finishCompletion(completion);
    }

    public PasskeyContext validatePasskey(
            String actor, UUID challengeId, HttpServletRequest request) {
        UUID actorId = owners.id(actor, false);
        Challenge challenge = readChallenge(challengeId, sessionHash(sessionId(request)));
        if (challenge == null || !challenge.userId().equals(actorId))
            throw error(HttpStatus.GONE, "reauthenticationExpired");
        if (challenge.attempts() >= 5)
            throw error(HttpStatus.TOO_MANY_REQUESTS, "reauthenticationLocked");
        var effective = policies.resolve(actorId);
        if (!effective.revision().equals(challenge.revision()))
            throw error(HttpStatus.GONE, "reauthenticationExpired");
        if (!supportedMethods(effective, actorId).contains(PrimaryMethod.PASSKEY.name()))
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "reauthenticationMethodUnavailable");
        return new PasskeyContext(
                actorId, effective.revision(), effective.primaryCredentialRevision());
    }

    /** Commits failed assertion attempts after the WebAuthn verifier rolls its transaction back. */
    public void recordPasskeyFailure(
            String actor, UUID challengeId, PasskeyContext expected, HttpServletRequest request) {
        if (expected == null || challengeId == null) return;
        String binding = sessionHash(sessionId(request));
        UUID actorId = owners.id(actor, false);
        if (!expected.userId().equals(actorId)) return;
        tx.executeWithoutResult(
                status -> {
                    policyLock.lockForAuthentication();
                    lockUser(actorId);
                    Challenge challenge = lockChallenge(challengeId, actorId, binding);
                    if (challenge == null || challenge.attempts() >= 5) return;
                    var effective = policies.resolve(actorId);
                    if (!challenge.revision().equals(expected.authenticationRevision())
                            || !effective.revision().equals(expected.authenticationRevision())
                            || !effective
                                    .primaryCredentialRevision()
                                    .equals(expected.primaryCredentialRevision())) return;
                    jdbc.sql(
                                    """
                            UPDATE platform_reauthentication_challenge
                               SET attempts=LEAST(attempts+1,5),
                                   state=CASE WHEN attempts+1>=5 THEN 'LOCKED' ELSE state END
                             WHERE id=:id AND user_id=:user AND state='PENDING' AND completed_at IS NULL
                            """)
                            .param("id", challengeId)
                            .param("user", actorId)
                            .update();
                });
    }

    public Ticket completePasskey(
            String actor,
            UUID challengeId,
            PasskeyContext proof,
            String factorCode,
            HttpServletRequest request) {
        if (proof == null || challengeId == null || factorCode != null && factorCode.length() > 64)
            throw error(HttpStatus.BAD_REQUEST, "reauthenticationInvalid");
        UUID actorId = owners.id(actor, false);
        String sessionId = sessionId(request);
        String sessionBinding = sessionHash(sessionId);
        Completion completion =
                tx.execute(
                        status -> {
                            policyLock.lockForAuthentication();
                            lockUser(actorId);
                            Challenge challenge =
                                    lockChallenge(challengeId, actorId, sessionBinding);
                            if (challenge == null)
                                return new Completion("reauthenticationExpired", null);
                            if (!proof.userId().equals(actorId))
                                return new Completion("reauthenticationExpired", null);
                            if (challenge.attempts() >= 5)
                                return new Completion("reauthenticationLocked", null);
                            var effective = policies.resolve(actorId);
                            if (!effective.revision().equals(challenge.revision())
                                    || !effective.revision().equals(proof.authenticationRevision())
                                    || !effective
                                            .primaryCredentialRevision()
                                            .equals(proof.primaryCredentialRevision())
                                    || !effective.allowedMethods().contains(PrimaryMethod.PASSKEY)
                                    || !effective.usableMethods().contains(PrimaryMethod.PASSKEY)
                                    || !methodAvailability.available(PrimaryMethod.PASSKEY))
                                return new Completion("reauthenticationExpired", null);
                            return completeWithFactor(
                                    challenge, effective, factorCode, sessionId, sessionBinding);
                        });
        if (completion != null && completion.failure() == null)
            refreshSessionAfterFactorRotation(completion, request);
        return finishCompletion(completion);
    }

    public Ticket complete(
            String actor,
            UUID challengeId,
            String method,
            String password,
            String factorCode,
            HttpServletRequest request) {
        if (!"PASSWORD".equals(method)
                || challengeId == null
                || password == null
                || password.length() > 1024
                || factorCode != null && factorCode.length() > 64)
            throw error(HttpStatus.BAD_REQUEST, "reauthenticationInvalid");
        String sessionId = sessionId(request);
        String sessionDigest = sessionHash(sessionId);
        Challenge preflight = readChallenge(challengeId, sessionDigest);
        UUID actorId = owners.id(actor, false);
        if (preflight == null || !preflight.userId().equals(actorId))
            throw error(HttpStatus.GONE, "reauthenticationExpired");
        var preflightPolicy = policies.resolve(actorId);
        if (!preflightPolicy.revision().equals(preflight.revision()))
            throw error(HttpStatus.GONE, "reauthenticationExpired");
        if (!supportedMethods(preflightPolicy, actorId).contains("PASSWORD"))
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "reauthenticationMethodUnavailable");
        String passwordSnapshot =
                jdbc.sql(
                                """
                        SELECT password_hash FROM platform_user
                         WHERE id=:id AND enabled AND deleted_at IS NULL
                        """)
                        .param("id", actorId)
                        .query(String.class)
                        .optional()
                        .orElseThrow(() -> error(HttpStatus.GONE, "reauthenticationExpired"));
        if (!PasswordHashCapacity.tryAcquire())
            throw error(HttpStatus.TOO_MANY_REQUESTS, "passwordHashCapacity");
        boolean passwordMatches;
        try {
            passwordMatches =
                    PasswordContract.compatibleWithStoredHash(password, passwordSnapshot)
                            && passwords.matches(password, passwordSnapshot);
        } finally {
            PasswordHashCapacity.release();
        }
        Completion result =
                tx.execute(
                        status -> {
                            policyLock.lockForAuthentication();
                            UUID userId = actorId;
                            if (!userId.equals(preflight.userId()))
                                return new Completion("reauthenticationExpired", null);
                            lockUser(userId);
                            Challenge challenge = lockChallenge(challengeId, userId, sessionDigest);
                            if (challenge == null)
                                return new Completion("reauthenticationExpired", null);
                            if (challenge.attempts() >= 5)
                                return new Completion("reauthenticationLocked", null);
                            var effective = policies.resolve(userId);
                            if (!effective.revision().equals(challenge.revision()))
                                return new Completion("reauthenticationExpired", null);
                            if (!effective.allowedMethods().contains(PrimaryMethod.PASSWORD)
                                    || !effective.usableMethods().contains(PrimaryMethod.PASSWORD))
                                return new Completion("reauthenticationMethodUnavailable", null);
                            String currentPasswordHash =
                                    jdbc.sql("SELECT password_hash FROM platform_user WHERE id=:id")
                                            .param("id", userId)
                                            .query(String.class)
                                            .optional()
                                            .orElse(null);
                            if (!Objects.equals(passwordSnapshot, currentPasswordHash))
                                return new Completion("reauthenticationExpired", null);
                            if (!passwordMatches) {
                                jdbc.sql(
                                                """
                                UPDATE platform_reauthentication_challenge
                                   SET attempts=LEAST(attempts+1,5),
                                       state=CASE WHEN attempts+1>=5 THEN 'LOCKED' ELSE state END
                                 WHERE id=:id AND state='PENDING'
                                """)
                                        .param("id", challengeId)
                                        .update();
                                return new Completion("reauthenticationInvalid", null);
                            }
                            return completeWithFactor(
                                    challenge, effective, factorCode, sessionId, sessionDigest);
                        });
        if (result != null && result.failure() == null)
            refreshSessionAfterFactorRotation(result, request);
        return finishCompletion(result);
    }

    private Ticket finishCompletion(Completion result) {
        if (result == null)
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "reauthenticationUnavailable");
        if (result.failure() != null) {
            HttpStatus status =
                    switch (result.failure()) {
                        case "reauthenticationInvalid" -> HttpStatus.BAD_REQUEST;
                        case "reauthenticationMethodUnavailable" -> HttpStatus.SERVICE_UNAVAILABLE;
                        case "reauthenticationLocked" -> HttpStatus.TOO_MANY_REQUESTS;
                        default -> HttpStatus.GONE;
                    };
            throw error(status, result.failure());
        }
        return result.ticket();
    }

    private Completion completeWithFactor(
            Challenge challenge,
            EffectiveAuthenticationPolicyPort.EffectivePolicy effective,
            String factorCode,
            String sessionId,
            String sessionDigest) {
        String verifiedFactor = null;
        if (effective.mfaRequired()) {
            if (factorCode == null || factorCode.isBlank())
                return failedAttempt(challenge, "reauthenticationInvalid");
            verifiedFactor = verifyFactor(challenge.userId(), factorCode, sessionId);
            if (verifiedFactor == null) return failedAttempt(challenge, "reauthenticationInvalid");
        } else if (factorCode != null && !factorCode.isBlank()) {
            return failedAttempt(challenge, "reauthenticationInvalid");
        }

        var finalPolicy = effective;
        if ("RECOVERY_CODE".equals(verifiedFactor)) {
            finalPolicy = policies.resolve(challenge.userId());
            if (!AuthenticationPolicyComparison.sameExceptFactorEpoch(effective, finalPolicy)
                    || effective
                            .factorCredentialRevision()
                            .equals(finalPolicy.factorCredentialRevision()))
                return new Completion("reauthenticationExpired", null);
        }
        UUID ticketId = UUID.randomUUID();
        Instant expires = Instant.now().plusSeconds(300);
        int completed =
                jdbc.sql(
                                """
                        UPDATE platform_reauthentication_challenge
                           SET completed_at=CURRENT_TIMESTAMP,ticket_id=:ticket,state='COMPLETED',
                               authentication_revision=:revision
                         WHERE id=:id AND user_id=:user AND state='PENDING' AND completed_at IS NULL
                        """)
                        .param("ticket", ticketId)
                        .param("revision", finalPolicy.revision())
                        .param("id", challenge.id())
                        .param("user", challenge.userId())
                        .update();
        if (completed != 1) return new Completion("reauthenticationExpired", null);
        jdbc.sql(
                        """
                        INSERT INTO platform_reauthentication_ticket
                            (id,challenge_id,user_id,session_hash,action,authentication_revision,expires_at)
                        VALUES (:id,:challenge,:user,:session,:action,:revision,:expires)
                        """)
                .param("id", ticketId)
                .param("challenge", challenge.id())
                .param("user", challenge.userId())
                .param("session", sessionDigest)
                .param("action", challenge.action())
                .param("revision", finalPolicy.revision())
                .param("expires", java.sql.Timestamp.from(expires))
                .update();
        List<String> factors = verifiedFactor == null ? List.of() : List.of(verifiedFactor);
        return new Completion(
                null,
                new Ticket(ticketId, expires, challenge.action()),
                finalPolicy.revision(),
                finalPolicy.factorCredentialRevision(),
                factors);
    }

    private Completion failedAttempt(Challenge challenge, String failure) {
        jdbc.sql(
                        """
                        UPDATE platform_reauthentication_challenge
                           SET attempts=LEAST(attempts+1,5),
                               state=CASE WHEN attempts+1>=5 THEN 'LOCKED' ELSE state END
                         WHERE id=:id AND user_id=:user AND state='PENDING' AND completed_at IS NULL
                        """)
                .param("id", challenge.id())
                .param("user", challenge.userId())
                .update();
        return new Completion(failure, null);
    }

    private void refreshSessionAfterFactorRotation(
            Completion completion, HttpServletRequest request) {
        if (!completion.verifiedFactors().contains("RECOVERY_CODE")) return;
        var session = request.getSession(false);
        if (session == null) throw error(HttpStatus.GONE, "reauthenticationExpired");
        session.setAttribute(
                AuthenticationSession.FACTOR_CREDENTIAL_REVISION,
                completion.factorCredentialRevision().toString());
        session.setAttribute(
                AuthenticationSession.AUTHENTICATION_REVISION,
                completion.authenticationRevision().toString());
        session.setAttribute(
                SecondFactorSession.REQUIREMENT_VERSION_ATTRIBUTE,
                completion.authenticationRevision().toString());
        session.setAttribute(AuthenticationSession.VERIFIED_FACTORS, completion.verifiedFactors());
    }

    @Override
    public void consume(UUID ticketId, UUID userId, Action action, String sessionId) {
        if (ticketId == null || userId == null || action == null || sessionId == null)
            throw error(HttpStatus.FORBIDDEN, "reauthenticationRequired");
        policyLock.lockForAuthentication();
        lockUser(userId);
        var ticket =
                jdbc.sql(
                                """
                        SELECT id,action,authentication_revision FROM platform_reauthentication_ticket
                         WHERE id=:id AND user_id=:user AND session_hash=:session
                           AND expires_at>CURRENT_TIMESTAMP AND consumed_at IS NULL
                         FOR UPDATE
                        """)
                        .param("id", ticketId)
                        .param("user", userId)
                        .param("session", sessionHash(sessionId))
                        .query(
                                (rs, row) ->
                                        new TicketRow(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("action"),
                                                rs.getObject(
                                                        "authentication_revision", UUID.class)))
                        .optional()
                        .orElseThrow(() -> error(HttpStatus.FORBIDDEN, "reauthenticationRequired"));
        if (!ticket.action().equals(action.name())
                || !ticket.revision().equals(policies.resolve(userId).revision()))
            throw error(HttpStatus.FORBIDDEN, "reauthenticationRequired");
        jdbc.sql(
                        "UPDATE platform_reauthentication_ticket SET consumed_at=CURRENT_TIMESTAMP WHERE id=:id AND consumed_at IS NULL")
                .param("id", ticketId)
                .update();
    }

    public void purgeExpired(int limit) {
        int bounded = Math.max(1, Math.min(limit, 5_000));
        jdbc.sql(
                        """
                        WITH expired AS (
                            SELECT id FROM platform_reauthentication_ticket
                             WHERE expires_at<=CURRENT_TIMESTAMP OR consumed_at IS NOT NULL
                             ORDER BY expires_at,id FOR UPDATE SKIP LOCKED LIMIT :limit
                        ) DELETE FROM platform_reauthentication_ticket t USING expired e WHERE t.id=e.id
                        """)
                .param("limit", bounded)
                .update();
        jdbc.sql(
                        """
                        WITH expired AS (
                            SELECT id FROM platform_reauthentication_code_challenge
                             WHERE expires_at<=CURRENT_TIMESTAMP OR state<>'PENDING'
                             ORDER BY expires_at,id FOR UPDATE SKIP LOCKED LIMIT :limit
                        ) DELETE FROM platform_reauthentication_code_challenge c USING expired e WHERE c.id=e.id
                        """)
                .param("limit", bounded)
                .update();
        jdbc.sql(
                        """
                        WITH expired AS (
                            SELECT c.id FROM platform_reauthentication_challenge c
                             WHERE (c.expires_at<=CURRENT_TIMESTAMP OR c.state<>'PENDING')
                               AND NOT EXISTS (
                                    SELECT 1 FROM platform_reauthentication_ticket t
                                     WHERE t.challenge_id=c.id AND t.consumed_at IS NULL
                                       AND t.expires_at>CURRENT_TIMESTAMP
                               )
                             ORDER BY c.expires_at,c.id FOR UPDATE SKIP LOCKED LIMIT :limit
                        ) DELETE FROM platform_reauthentication_challenge c USING expired e WHERE c.id=e.id
                        """)
                .param("limit", bounded)
                .update();
    }

    private List<String> supportedMethods(
            EffectiveAuthenticationPolicyPort.EffectivePolicy effective, UUID userId) {
        var methods = new ArrayList<String>();
        if (effective.allowedMethods().contains(PrimaryMethod.PASSWORD)
                && effective.usableMethods().contains(PrimaryMethod.PASSWORD)
                && methodAvailability.available(PrimaryMethod.PASSWORD)) methods.add("PASSWORD");
        if (effective.allowedMethods().contains(PrimaryMethod.EMAIL_OTP)
                && effective.usableMethods().contains(PrimaryMethod.EMAIL_OTP)
                && methodAvailability.available(PrimaryMethod.EMAIL_OTP)
                && verifiedContactExists(userId, "EMAIL")) methods.add("EMAIL_OTP");
        if (effective.allowedMethods().contains(PrimaryMethod.SMS_OTP)
                && effective.usableMethods().contains(PrimaryMethod.SMS_OTP)
                && methodAvailability.available(PrimaryMethod.SMS_OTP)
                && verifiedContactExists(userId, "SMS")) methods.add("SMS_OTP");
        if (effective.allowedMethods().contains(PrimaryMethod.PASSKEY)
                && effective.usableMethods().contains(PrimaryMethod.PASSKEY)
                && methodAvailability.available(PrimaryMethod.PASSKEY)) methods.add("PASSKEY");
        // A WeChat login credential cannot prove a particular sensitive action until the provider
        // offers an action-bound assertion. Do not silently treat a login callback as reauth.
        return List.copyOf(methods);
    }

    private boolean verifiedContactExists(UUID userId, String channel) {
        String sql =
                switch (channel) {
                    case "EMAIL" ->
                            "SELECT EXISTS(SELECT 1 FROM platform_account_email WHERE user_id=:id AND email IS NOT NULL AND verified_at IS NOT NULL)";
                    case "SMS" ->
                            "SELECT EXISTS(SELECT 1 FROM platform_account_phone WHERE user_id=:id AND phone IS NOT NULL AND verified_at IS NOT NULL)";
                    default -> throw new IllegalArgumentException("invalidContactChannel");
                };
        return jdbc.sql(sql).param("id", userId).query(Boolean.class).single();
    }

    private String verifyFactor(UUID userId, String code, String retainedSessionId) {
        var availableProviders =
                plugins
                        .extensions(
                                SecondFactorProvider.class,
                                provider -> {
                                    if (!provider.id().equals("mfa") || !provider.available())
                                        return new FactorResult(false, false, null);
                                    var status = provider.status(userId);
                                    if (status == null || !status.configured())
                                        return new FactorResult(true, false, null);
                                    return new FactorResult(
                                            true,
                                            true,
                                            provider.verifyEvidence(
                                                    userId, code, retainedSessionId));
                                })
                        .stream()
                        .filter(FactorResult::available)
                        .toList();
        if (availableProviders.size() != 1)
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "secondFactorUnavailable");
        var provider = availableProviders.getFirst();
        if (!provider.configured())
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "secondFactorUnavailable");
        return provider.factor();
    }

    private Challenge readChallenge(UUID id, String sessionDigest) {
        return jdbc.sql(
                        """
                        SELECT id,user_id,action,authentication_revision,attempts,expires_at
                          FROM platform_reauthentication_challenge
                         WHERE id=:id AND session_hash=:session AND expires_at>CURRENT_TIMESTAMP
                           AND state='PENDING' AND completed_at IS NULL
                        """)
                .param("id", id)
                .param("session", sessionDigest)
                .query(ReauthenticationService::mapChallenge)
                .optional()
                .orElse(null);
    }

    private Challenge lockChallenge(UUID id, UUID userId, String sessionDigest) {
        return jdbc.sql(
                        """
                        SELECT id,user_id,action,authentication_revision,attempts,expires_at
                          FROM platform_reauthentication_challenge
                         WHERE id=:id AND user_id=:user AND session_hash=:session
                           AND expires_at>CURRENT_TIMESTAMP AND completed_at IS NULL
                           AND state='PENDING'
                         FOR UPDATE
                        """)
                .param("id", id)
                .param("user", userId)
                .param("session", sessionDigest)
                .query(ReauthenticationService::mapChallenge)
                .optional()
                .orElse(null);
    }

    private CodeRow lockCodeChallenge(UUID id, UUID parentId, UUID userId, String sessionDigest) {
        return jdbc.sql(
                        """
                        SELECT id,reauthentication_challenge_id,user_id,method,contact_digest,
                               contact_version,primary_credential_revision,factor_credential_revision,
                               code_digest,state,attempts,expires_at
                          FROM platform_reauthentication_code_challenge
                         WHERE id=:id AND reauthentication_challenge_id=:parent AND user_id=:user
                           AND session_hash=:session
                         FOR UPDATE
                        """)
                .param("id", id)
                .param("parent", parentId)
                .param("user", userId)
                .param("session", sessionDigest)
                .query(
                        (rs, row) ->
                                new CodeRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("reauthentication_challenge_id", UUID.class),
                                        rs.getObject("user_id", UUID.class),
                                        rs.getString("method"),
                                        rs.getString("contact_digest"),
                                        rs.getLong("contact_version"),
                                        rs.getObject("primary_credential_revision", UUID.class),
                                        rs.getObject("factor_credential_revision", UUID.class),
                                        rs.getString("code_digest"),
                                        rs.getString("state"),
                                        rs.getInt("attempts"),
                                        rs.getObject("expires_at", OffsetDateTime.class)))
                .optional()
                .orElse(null);
    }

    private void incrementCodeAttempts(UUID id) {
        jdbc.sql(
                        """
                        UPDATE platform_reauthentication_code_challenge
                           SET attempts=LEAST(attempts+1,5),
                               state=CASE WHEN attempts+1>=5 THEN 'LOCKED' ELSE state END
                         WHERE id=:id AND state='PENDING'
                        """)
                .param("id", id)
                .update();
    }

    private void consumeCodeChallenge(UUID id) {
        int consumed =
                jdbc.sql(
                                """
                        UPDATE platform_reauthentication_code_challenge
                           SET state='CONSUMED',consumed_at=CURRENT_TIMESTAMP
                         WHERE id=:id AND state='PENDING' AND consumed_at IS NULL
                        """)
                        .param("id", id)
                        .update();
        if (consumed != 1) throw error(HttpStatus.GONE, "reauthenticationExpired");
    }

    private static PrimaryMethod parseReauthenticationMethod(String value) {
        try {
            PrimaryMethod method = PrimaryMethod.valueOf(value == null ? "" : value);
            if (method != PrimaryMethod.EMAIL_OTP && method != PrimaryMethod.SMS_OTP)
                throw new IllegalArgumentException();
            return method;
        } catch (IllegalArgumentException invalid) {
            throw error(HttpStatus.BAD_REQUEST, "reauthenticationInvalid");
        }
    }

    private static String channel(PrimaryMethod method) {
        return switch (method) {
            case EMAIL_OTP -> "EMAIL";
            case SMS_OTP -> "SMS";
            default -> throw error(HttpStatus.BAD_REQUEST, "reauthenticationInvalid");
        };
    }

    private static String normalizeContact(String channel, String value) {
        if (value == null || value.isBlank()) return null;
        if ("EMAIL".equals(channel)) {
            String normalized = value.strip().toLowerCase(Locale.ROOT);
            return normalized.length() <= 254 && normalized.contains("@") ? normalized : null;
        }
        try {
            return AccountPhoneService.normalize(value);
        } catch (ResponseStatusException invalid) {
            return null;
        }
    }

    private static Challenge mapChallenge(java.sql.ResultSet rs, int row)
            throws java.sql.SQLException {
        return new Challenge(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("action"),
                rs.getObject("authentication_revision", UUID.class),
                rs.getInt("attempts"),
                rs.getTimestamp("expires_at").toInstant());
    }

    private void lockUser(UUID userId) {
        jdbc.sql(
                        "SELECT id FROM platform_user WHERE id=:id AND enabled AND deleted_at IS NULL FOR UPDATE")
                .param("id", userId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> error(HttpStatus.UNAUTHORIZED, "authenticationRequired"));
    }

    private static String sessionId(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) throw error(HttpStatus.UNAUTHORIZED, "authenticationRequired");
        return session.getId();
    }

    private static String sessionHash(String sessionId) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    ("lumira-reauth-session:" + sessionId)
                                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static ResponseStatusException error(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }

    private record TicketRow(UUID id, String action, UUID revision) {}
}
