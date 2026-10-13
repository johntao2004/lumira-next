package com.lumira.platform.identity;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.FixedWindowBuckets;
import com.lumira.platform.common.PlatformOperation;
import com.lumira.platform.identity.api.EffectiveAuthenticationPolicyPort;
import com.lumira.platform.identity.api.PasswordContract;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import com.lumira.platform.identity.api.TemporaryPasswordResetPort;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Issues one-time temporary credentials and consumes session-bound forced-change challenges. */
@Service
public class TemporaryPasswordResetService implements TemporaryPasswordResetPort {
    private static final PlatformOperation RESET_OPERATION = PlatformOperation.USER_RESET_PASSWORD;
    static final int TEMPORARY_PASSWORD_TTL_SECONDS = 30 * 60;
    private static final String REQUIREMENT_TABLE = "platform_password_change_requirement";
    private static final String PENDING_SESSION_ATTRIBUTE = SecondFactorLoginService.PENDING;
    private static final char[] PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*-_+".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Requirement(
            UUID credentialRevision, Instant expiresAt, Instant claimedAt, boolean active) {}

    private record UserSnapshot(
            UUID id,
            String username,
            String passwordHash,
            UUID credentialRevision,
            long version,
            boolean enabled) {}

    private record ChangeChallenge(String state, UUID policyRevision) {}

    private record ChangeSnapshot(
            String passwordHash,
            UUID credentialRevision,
            SecurityPolicyPort.Policy securityPolicy) {}

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AdministrationPort administration;
    private final AuthenticationPolicyWriteLock policyLock;
    private final EffectiveAuthenticationPolicyPort policies;
    private final SecurityPolicyPort security;
    private final AuditPort audit;
    private final PasswordChangeLimiter limiter;
    private final TransactionTemplate transaction;

    @Autowired
    public TemporaryPasswordResetService(
            JdbcClient jdbc,
            PasswordEncoder encoder,
            AdministrationPort administration,
            AuthenticationPolicyWriteLock policyLock,
            EffectiveAuthenticationPolicyPort policies,
            SecurityPolicyPort security,
            AuditPort audit,
            PasswordChangeLimiter limiter,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.administration = administration;
        this.policyLock = policyLock;
        this.policies = policies;
        this.security = security;
        this.audit = audit;
        this.limiter = limiter;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setTimeout(10);
    }

    /**
     * Authorization and visibility are checked before Argon2 work, then repeated under the shared
     * administration lock before the versioned update commits.
     */
    @Override
    public TemporaryPasswordResetPort.ResetResult reset(
            UUID userId, Long expectedVersion, String actor) {
        if (userId == null || expectedVersion == null || expectedVersion < 0 || actor == null)
            throw error(HttpStatus.BAD_REQUEST, "invalidPasswordReset");

        UserSnapshot preflight = authorizedSnapshot(userId, expectedVersion, actor, false);
        protectTarget(preflight, actor);
        SecurityPolicyPort.Policy policySnapshot = currentPolicy();
        String temporaryPassword = generateTemporaryPassword(policySnapshot);
        if (!PasswordHashCapacity.tryAcquire())
            throw error(HttpStatus.TOO_MANY_REQUESTS, "passwordHashCapacity");
        String encoded;
        try {
            encoded = encoder.encode(temporaryPassword);
        } finally {
            PasswordHashCapacity.release();
        }

        return transaction.execute(
                status -> {
                    UserSnapshot current = authorizedSnapshot(userId, expectedVersion, actor, true);
                    protectTarget(current, actor);
                    if (!Objects.equals(policySnapshot, currentPolicy())
                            || !PasswordPolicy.valid(temporaryPassword, currentPolicy()))
                        throw error(HttpStatus.CONFLICT, "passwordPolicyChanged");
                    UUID revision =
                            jdbc.sql(
                                            "UPDATE platform_user SET password_hash=:hash,primary_credential_revision=gen_random_uuid(),version=version+1 WHERE id=:id AND version=:version AND enabled AND deleted_at IS NULL RETURNING primary_credential_revision")
                                    .param("hash", encoded)
                                    .param("id", userId)
                                    .param("version", expectedVersion)
                                    .query(UUID.class)
                                    .optional()
                                    .orElseThrow(() -> error(HttpStatus.CONFLICT, "staleRecord"));
                    jdbc.sql("DELETE FROM spring_session WHERE principal_name=:name")
                            .param("name", current.username())
                            .update();
                    jdbc.sql("DELETE FROM platform_login_challenge WHERE user_id=:id")
                            .param("id", userId)
                            .update();
                    Instant expiresAt =
                            jdbc.sql(
                                            "INSERT INTO platform_password_change_requirement(user_id,primary_credential_revision,expires_at,issued_by) VALUES(:id,:revision,CURRENT_TIMESTAMP+(:ttl * interval '1 second'),(SELECT id FROM platform_user WHERE username=:actor)) ON CONFLICT(user_id) DO UPDATE SET primary_credential_revision=EXCLUDED.primary_credential_revision,expires_at=EXCLUDED.expires_at,claimed_at=NULL,issued_by=EXCLUDED.issued_by,created_at=CURRENT_TIMESTAMP RETURNING expires_at")
                                    .param("id", userId)
                                    .param("revision", revision)
                                    .param("ttl", TEMPORARY_PASSWORD_TTL_SECONDS)
                                    .param("actor", actor)
                                    .query(Instant.class)
                                    .single();
                    audit.append(actor, "user.passwordReset", userId);
                    return new TemporaryPasswordResetPort.ResetResult(temporaryPassword, expiresAt);
                });
    }

    /**
     * Must be called in the shared primary-authentication transaction while the user row is locked.
     */
    Requirement lockRequirement(UUID userId) {
        return jdbc.sql(
                        "SELECT primary_credential_revision,expires_at,claimed_at,expires_at>CURRENT_TIMESTAMP AS active FROM "
                                + REQUIREMENT_TABLE
                                + " WHERE user_id=:id FOR UPDATE")
                .param("id", userId)
                .query(
                        (rs, row) ->
                                new Requirement(
                                        rs.getObject("primary_credential_revision", UUID.class),
                                        rs.getTimestamp("expires_at").toInstant(),
                                        rs.getTimestamp("claimed_at") == null
                                                ? null
                                                : rs.getTimestamp("claimed_at").toInstant(),
                                        rs.getBoolean("active")))
                .optional()
                .orElse(null);
    }

    /**
     * Claims the generated password only after its stored hash has been verified by the password
     * provider, or after another valid primary method has opened the mandatory-change stage.
     */
    boolean claim(UUID userId, UUID credentialRevision) {
        return jdbc.sql(
                                "UPDATE "
                                        + REQUIREMENT_TABLE
                                        + " SET claimed_at=COALESCE(claimed_at,CURRENT_TIMESTAMP) WHERE user_id=:id AND primary_credential_revision=:revision AND claimed_at IS NULL AND expires_at>CURRENT_TIMESTAMP")
                        .param("id", userId)
                        .param("revision", credentialRevision)
                        .update()
                == 1;
    }

    void discardObsolete(UUID userId, UUID currentCredentialRevision) {
        jdbc.sql(
                        "DELETE FROM "
                                + REQUIREMENT_TABLE
                                + " WHERE user_id=:id AND primary_credential_revision<>:revision")
                .param("id", userId)
                .param("revision", currentCredentialRevision)
                .update();
    }

    public void changePassword(
            UUID challengeId,
            String newPassword,
            String confirmPassword,
            HttpServletRequest request) {
        String sessionDigest = sessionHash(request);
        if (challengeId == null
                || request.getSession(false) == null
                || !challengeId.toString().equals(pendingId(request)))
            throw error(HttpStatus.BAD_REQUEST, "secondFactorExpired");
        if (!limiter.allowPendingSession(sessionDigest))
            throw error(HttpStatus.TOO_MANY_REQUESTS, "passwordChangeLimited");
        if (!Objects.equals(newPassword, confirmPassword))
            throw error(HttpStatus.BAD_REQUEST, "passwordConfirmationMismatch");
        SecurityPolicyPort.Policy policySnapshot = currentPolicy();
        if (!PasswordPolicy.valid(newPassword, policySnapshot))
            throw error(HttpStatus.BAD_REQUEST, "passwordPolicy");

        ChangeSnapshot preflight =
                transaction.execute(status -> lockChangeSnapshot(challengeId, request));
        if (!Objects.equals(policySnapshot, preflight.securityPolicy())
                || !PasswordPolicy.valid(newPassword, preflight.securityPolicy()))
            throw error(HttpStatus.CONFLICT, "passwordPolicyChanged");
        if (!PasswordHashCapacity.tryAcquire())
            throw error(HttpStatus.TOO_MANY_REQUESTS, "passwordHashCapacity");
        String replacementHash;
        try {
            if (PasswordContract.compatibleWithStoredHash(newPassword, preflight.passwordHash())
                    && encoder.matches(newPassword, preflight.passwordHash()))
                throw error(HttpStatus.BAD_REQUEST, "newPasswordMustDiffer");
            replacementHash = encoder.encode(newPassword);
        } finally {
            PasswordHashCapacity.release();
        }

        transaction.executeWithoutResult(
                status -> {
                    policyLock.lockForAuthentication();
                    UUID userId = challengeUser(challengeId, request);
                    if (userId == null) throw error(HttpStatus.BAD_REQUEST, "secondFactorExpired");
                    UserSnapshot user = lockUser(userId);
                    ChangeChallenge challenge = lockChallenge(challengeId, request, userId);
                    Requirement requirement = lockRequirement(userId);
                    var effective = policies.resolve(userId);
                    SecurityPolicyPort.Policy latestPolicy = currentPolicy();
                    if (challenge == null
                            || !"PASSWORD_CHANGE_REQUIRED".equals(challenge.state())
                            || !effective.revision().equals(challenge.policyRevision())
                            || !effective
                                    .primaryCredentialRevision()
                                    .equals(user.credentialRevision())
                            || requirement == null
                            || !requirement.credentialRevision().equals(user.credentialRevision())
                            || !user.credentialRevision().equals(preflight.credentialRevision())
                            || !Objects.equals(user.passwordHash(), preflight.passwordHash()))
                        throw error(HttpStatus.BAD_REQUEST, "secondFactorExpired");
                    if (!Objects.equals(preflight.securityPolicy(), latestPolicy)
                            || !PasswordPolicy.valid(newPassword, latestPolicy))
                        throw error(HttpStatus.CONFLICT, "passwordPolicyChanged");
                    String username =
                            jdbc.sql(
                                            "UPDATE platform_user SET password_hash=:hash,primary_credential_revision=gen_random_uuid(),version=version+1 WHERE id=:id AND password_hash=:expected AND primary_credential_revision=:revision AND enabled AND deleted_at IS NULL RETURNING username")
                                    .param("hash", replacementHash)
                                    .param("id", userId)
                                    .param("expected", preflight.passwordHash())
                                    .param("revision", preflight.credentialRevision())
                                    .query(String.class)
                                    .optional()
                                    .orElseThrow(
                                            () ->
                                                    error(
                                                            HttpStatus.CONFLICT,
                                                            "secondFactorExpired"));
                    jdbc.sql("DELETE FROM " + REQUIREMENT_TABLE + " WHERE user_id=:id")
                            .param("id", userId)
                            .update();
                    jdbc.sql("DELETE FROM platform_login_challenge WHERE user_id=:id")
                            .param("id", userId)
                            .update();
                    jdbc.sql("DELETE FROM spring_session WHERE principal_name=:name")
                            .param("name", username)
                            .update();
                    audit.append(username, "account.forcedPasswordChanged", userId);
                });
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

    private ChangeSnapshot lockChangeSnapshot(UUID challengeId, HttpServletRequest request) {
        policyLock.lockForAuthentication();
        UUID userId = challengeUser(challengeId, request);
        if (userId == null) throw error(HttpStatus.BAD_REQUEST, "secondFactorExpired");
        UserSnapshot user = lockUser(userId);
        ChangeChallenge challenge = lockChallenge(challengeId, request, userId);
        Requirement requirement = lockRequirement(userId);
        var effective = policies.resolve(userId);
        if (challenge == null
                || !"PASSWORD_CHANGE_REQUIRED".equals(challenge.state())
                || !effective.revision().equals(challenge.policyRevision())
                || !effective.primaryCredentialRevision().equals(user.credentialRevision())
                || requirement == null
                || !requirement.credentialRevision().equals(user.credentialRevision()))
            throw error(HttpStatus.BAD_REQUEST, "secondFactorExpired");
        return new ChangeSnapshot(user.passwordHash(), user.credentialRevision(), currentPolicy());
    }

    private ChangeChallenge lockChallenge(UUID id, HttpServletRequest request, UUID userId) {
        return jdbc.sql(
                        "SELECT state,policy_revision FROM platform_login_challenge WHERE id=:id AND user_id=:user AND session_hash=:session AND expires_at>CURRENT_TIMESTAMP FOR UPDATE")
                .param("id", id)
                .param("user", userId)
                .param("session", sessionHash(request))
                .query(
                        (rs, row) ->
                                new ChangeChallenge(
                                        rs.getString("state"),
                                        rs.getObject("policy_revision", UUID.class)))
                .optional()
                .orElse(null);
    }

    private UUID challengeUser(UUID challengeId, HttpServletRequest request) {
        return jdbc.sql(
                        "SELECT user_id FROM platform_login_challenge WHERE id=:id AND session_hash=:session AND expires_at>CURRENT_TIMESTAMP")
                .param("id", challengeId)
                .param("session", sessionHash(request))
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private UserSnapshot lockUser(UUID id) {
        return jdbc.sql(
                        "SELECT id,username,password_hash,primary_credential_revision,version,enabled FROM platform_user WHERE id=:id AND enabled AND deleted_at IS NULL FOR UPDATE")
                .param("id", id)
                .query(
                        (rs, row) ->
                                new UserSnapshot(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("username"),
                                        rs.getString("password_hash"),
                                        rs.getObject("primary_credential_revision", UUID.class),
                                        rs.getLong("version"),
                                        rs.getBoolean("enabled")))
                .optional()
                .orElseThrow(() -> error(HttpStatus.BAD_REQUEST, "secondFactorExpired"));
    }

    private UserSnapshot authorizedSnapshot(
            UUID id, long expectedVersion, String actor, boolean lock) {
        administration.requireOperationUser(actor, id, RESET_OPERATION);
        if (lock) policyLock.lockForAuthentication();
        String sql =
                "SELECT id,username,password_hash,primary_credential_revision,version,enabled FROM platform_user WHERE id=:id AND deleted_at IS NULL"
                        + (lock ? " FOR UPDATE" : "");
        UserSnapshot user =
                jdbc.sql(sql)
                        .param("id", id)
                        .query(
                                (rs, row) ->
                                        new UserSnapshot(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("username"),
                                                rs.getString("password_hash"),
                                                rs.getObject(
                                                        "primary_credential_revision", UUID.class),
                                                rs.getLong("version"),
                                                rs.getBoolean("enabled")))
                        .optional()
                        .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "userNotFound"));
        if (!user.enabled()) throw error(HttpStatus.CONFLICT, "protectedAccount");
        if (user.version() != expectedVersion) throw error(HttpStatus.CONFLICT, "staleRecord");
        return user;
    }

    private void protectTarget(UserSnapshot user, String actor) {
        if (user.username().equals(actor)
                || jdbc.sql("SELECT platform_is_last_recovery_administrator(:id)")
                        .param("id", user.id())
                        .query(Boolean.class)
                        .single()) throw error(HttpStatus.CONFLICT, "protectedAccount");
    }

    private SecurityPolicyPort.Policy currentPolicy() {
        return security == null ? SecurityPolicyPort.Policy.defaults() : security.currentPolicy();
    }

    private static String generateTemporaryPassword(SecurityPolicyPort.Policy policy) {
        int minimum = policy.passwordMinLength() == null ? 12 : policy.passwordMinLength();
        int length = Math.max(32, minimum);
        if (length > PasswordContract.MAX_PASSWORD_BYTES)
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "passwordPolicyUnavailable");
        for (int attempt = 0; attempt < 256; attempt++) {
            StringBuilder candidate = new StringBuilder(length);
            for (int index = 0; index < length; index++)
                candidate.append(PASSWORD_ALPHABET[RANDOM.nextInt(PASSWORD_ALPHABET.length)]);
            String value = candidate.toString();
            if (PasswordPolicy.valid(value, policy)) return value;
        }
        throw error(HttpStatus.SERVICE_UNAVAILABLE, "passwordPolicyUnavailable");
    }

    private static String pendingId(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        Object value = session.getAttribute(PENDING_SESSION_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }

    private static String sessionHash(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return "";
        return FixedWindowBuckets.key("mfa-session", session.getId()).substring(12);
    }

    private static ResponseStatusException error(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }
}
