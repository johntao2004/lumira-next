package com.lumira.platform.mfa;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class MfaStore {
    record Authenticator(
            String envelope,
            String label,
            boolean confirmed,
            Instant expires,
            long lastStep,
            Instant created,
            UUID setupChallengeId,
            String setupSessionHash,
            int setupAttempts) {}

    record Replacement(
            String envelope,
            String label,
            UUID challengeId,
            String sessionHash,
            UUID factorCredentialRevision,
            UUID authenticationRevision,
            Instant expires,
            long lastStep,
            int attempts) {}

    private final JdbcClient jdbc;

    public MfaStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Authenticator> find(UUID id) {
        return queryAuthenticator(id, false);
    }

    Optional<Authenticator> findForUpdate(UUID id) {
        return queryAuthenticator(id, true);
    }

    private Optional<Authenticator> queryAuthenticator(UUID id, boolean lock) {
        return jdbc.sql(
                        """
                        SELECT secret_envelope,label,confirmed,expires_at,last_step,created_at,
                               setup_challenge_id,setup_session_hash,setup_attempts
                          FROM plugin_mfa_authenticator WHERE user_id=:id
                        """
                                + (lock ? " FOR UPDATE" : ""))
                .param("id", id)
                .query(
                        (r, n) ->
                                new Authenticator(
                                        r.getString("secret_envelope"),
                                        r.getString("label"),
                                        r.getBoolean("confirmed"),
                                        r.getTimestamp("expires_at") == null
                                                ? null
                                                : r.getTimestamp("expires_at").toInstant(),
                                        r.getLong("last_step"),
                                        r.getTimestamp("created_at").toInstant(),
                                        r.getObject("setup_challenge_id", UUID.class),
                                        r.getString("setup_session_hash"),
                                        r.getInt("setup_attempts")))
                .optional();
    }

    boolean required(UUID id) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_second_factor_requirement WHERE user_id=:id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    int recoveryCount(UUID id) {
        return jdbc.sql("SELECT count(*) FROM plugin_mfa_recovery WHERE user_id=:id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    void setup(
            UUID id,
            String label,
            String envelope,
            Instant expires,
            UUID challengeId,
            String sessionHash) {
        jdbc.sql(
                        """
                        INSERT INTO plugin_mfa_authenticator
                            (user_id,label,secret_envelope,expires_at,setup_challenge_id,setup_session_hash)
                        VALUES (:id,:label,:envelope,:expires,:challenge,:session)
                        ON CONFLICT(user_id) DO UPDATE SET label=:label,secret_envelope=:envelope,
                            confirmed=false,expires_at=:expires,last_step=-1,created_at=CURRENT_TIMESTAMP,
                            setup_challenge_id=:challenge,setup_session_hash=:session,setup_attempts=0
                        """)
                .param("id", id)
                .param("label", label)
                .param("envelope", envelope)
                .param("expires", java.sql.Timestamp.from(expires))
                .param("challenge", challengeId)
                .param("session", sessionHash)
                .update();
    }

    void activate(UUID id, UUID challengeId, long step) {
        int updated =
                jdbc.sql(
                                """
                        UPDATE plugin_mfa_authenticator
                           SET confirmed=true,expires_at=NULL,last_step=:step,
                               setup_challenge_id=NULL,setup_session_hash=NULL,setup_attempts=0
                         WHERE user_id=:id AND setup_challenge_id=:challenge AND confirmed=false
                        """)
                        .param("step", step)
                        .param("id", id)
                        .param("challenge", challengeId)
                        .update();
        if (updated != 1) throw new ResponseStatusException(HttpStatus.GONE, "mfaSetupExpired");
    }

    int incrementSetupAttempts(UUID id, UUID challengeId) {
        return jdbc.sql(
                        """
                        UPDATE plugin_mfa_authenticator
                           SET setup_attempts=LEAST(setup_attempts+1,5)
                         WHERE user_id=:id AND setup_challenge_id=:challenge AND confirmed=false
                         RETURNING setup_attempts
                        """)
                .param("id", id)
                .param("challenge", challengeId)
                .query(Integer.class)
                .optional()
                .orElse(5);
    }

    Optional<Replacement> replacementForUpdate(UUID id) {
        return jdbc.sql(
                        """
                        SELECT secret_envelope,label,challenge_id,session_hash,
                               factor_credential_revision,authentication_revision,expires_at,last_step,attempts
                          FROM plugin_mfa_replacement WHERE user_id=:id FOR UPDATE
                        """)
                .param("id", id)
                .query(
                        (rs, row) ->
                                new Replacement(
                                        rs.getString("secret_envelope"),
                                        rs.getString("label"),
                                        rs.getObject("challenge_id", UUID.class),
                                        rs.getString("session_hash"),
                                        rs.getObject("factor_credential_revision", UUID.class),
                                        rs.getObject("authentication_revision", UUID.class),
                                        rs.getTimestamp("expires_at").toInstant(),
                                        rs.getLong("last_step"),
                                        rs.getInt("attempts")))
                .optional();
    }

    void replacementSetup(
            UUID id,
            String label,
            String envelope,
            UUID challengeId,
            String sessionHash,
            UUID factorCredentialRevision,
            UUID authenticationRevision,
            Instant expires) {
        jdbc.sql(
                        """
                        INSERT INTO plugin_mfa_replacement
                            (user_id,label,secret_envelope,challenge_id,session_hash,
                             factor_credential_revision,authentication_revision,expires_at,last_step,attempts)
                        VALUES (:id,:label,:envelope,:challenge,:session,:revision,:authenticationRevision,:expires,-1,0)
                        ON CONFLICT(user_id) DO UPDATE SET label=:label,secret_envelope=:envelope,
                            challenge_id=:challenge,session_hash=:session,
                            factor_credential_revision=:revision,authentication_revision=:authenticationRevision,
                            expires_at=:expires,last_step=-1,attempts=0,
                            created_at=CURRENT_TIMESTAMP
                        """)
                .param("id", id)
                .param("label", label)
                .param("envelope", envelope)
                .param("challenge", challengeId)
                .param("session", sessionHash)
                .param("revision", factorCredentialRevision)
                .param("authenticationRevision", authenticationRevision)
                .param("expires", java.sql.Timestamp.from(expires))
                .update();
    }

    int incrementReplacementAttempts(UUID id, UUID challengeId) {
        return jdbc.sql(
                        """
                        UPDATE plugin_mfa_replacement
                           SET attempts=LEAST(attempts+1,5)
                         WHERE user_id=:id AND challenge_id=:challenge AND attempts<5
                         RETURNING attempts
                        """)
                .param("id", id)
                .param("challenge", challengeId)
                .query(Integer.class)
                .optional()
                .orElse(5);
    }

    int activateReplacement(UUID id, UUID challengeId, long step, String activeEnvelope) {
        Replacement replacement =
                replacementForUpdate(id)
                        .filter(value -> value.challengeId().equals(challengeId))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.GONE, "mfaReplacementExpired"));
        int updated =
                jdbc.sql(
                                """
                        UPDATE plugin_mfa_authenticator
                           SET label=:label,secret_envelope=:envelope,confirmed=true,expires_at=NULL,
                               last_step=:step,setup_challenge_id=NULL,setup_session_hash=NULL,
                               setup_attempts=0,created_at=CURRENT_TIMESTAMP
                         WHERE user_id=:id AND confirmed=true
                        """)
                        .param("id", id)
                        .param("label", replacement.label())
                        .param("envelope", activeEnvelope)
                        .param("step", step)
                        .update();
        if (updated == 1) deleteReplacement(id, challengeId);
        return updated;
    }

    int deleteReplacement(UUID id, UUID challengeId) {
        return jdbc.sql(
                        "DELETE FROM plugin_mfa_replacement WHERE user_id=:id AND challenge_id=:challenge")
                .param("id", id)
                .param("challenge", challengeId)
                .update();
    }

    int cancelReplacement(UUID id, UUID challengeId, String sessionHash) {
        return jdbc.sql(
                        """
                        DELETE FROM plugin_mfa_replacement
                         WHERE user_id=:id AND challenge_id=:challenge AND session_hash=:session
                        """)
                .param("id", id)
                .param("challenge", challengeId)
                .param("session", sessionHash)
                .update();
    }

    boolean allowRecoveryRequest(UUID id, int cooldownSeconds, int dailyLimit) {
        return jdbc.sql(
                        """
                        INSERT INTO platform_mfa_recovery_request_rate
                            (user_id,requested_at,request_day,request_count)
                        VALUES (:id,CURRENT_TIMESTAMP,CURRENT_DATE,1)
                        ON CONFLICT(user_id) DO UPDATE
                           SET requested_at=CURRENT_TIMESTAMP,
                               request_count=CASE
                                   WHEN platform_mfa_recovery_request_rate.request_day IS DISTINCT FROM CURRENT_DATE THEN 1
                                   ELSE platform_mfa_recovery_request_rate.request_count+1
                               END,
                               request_day=CURRENT_DATE
                         WHERE (platform_mfa_recovery_request_rate.requested_at IS NULL
                                   OR platform_mfa_recovery_request_rate.requested_at
                                      <= CURRENT_TIMESTAMP-(:cooldown * interval '1 second'))
                           AND (platform_mfa_recovery_request_rate.request_day IS DISTINCT FROM CURRENT_DATE
                                OR platform_mfa_recovery_request_rate.request_count<:daily)
                        RETURNING user_id
                        """)
                .param("id", id)
                .param("cooldown", cooldownSeconds)
                .param("daily", dailyLimit)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    void step(UUID id, long step) {
        jdbc.sql(
                        "UPDATE plugin_mfa_authenticator SET last_step=:step WHERE user_id=:id AND confirmed")
                .param("id", id)
                .param("step", step)
                .update();
    }

    List<String> recoveryDigests(UUID id) {
        return jdbc.sql("SELECT digest FROM plugin_mfa_recovery WHERE user_id=:id")
                .param("id", id)
                .query(String.class)
                .list();
    }

    boolean consumeRecovery(UUID id, String digest) {
        return jdbc.sql("DELETE FROM plugin_mfa_recovery WHERE user_id=:id AND digest=:digest")
                        .param("id", id)
                        .param("digest", digest)
                        .update()
                == 1;
    }

    void clearRecovery(UUID id) {
        jdbc.sql("DELETE FROM plugin_mfa_recovery WHERE user_id=:id").param("id", id).update();
    }

    void recovery(UUID id, String digest) {
        jdbc.sql("INSERT INTO plugin_mfa_recovery(user_id,digest) VALUES(:id,:digest)")
                .param("id", id)
                .param("digest", digest)
                .update();
    }

    void rename(UUID id, String label) {
        jdbc.sql("UPDATE plugin_mfa_authenticator SET label=:label WHERE user_id=:id AND confirmed")
                .param("id", id)
                .param("label", label)
                .update();
    }

    void delete(UUID id) {
        jdbc.sql("DELETE FROM plugin_mfa_authenticator WHERE user_id=:id").param("id", id).update();
    }

    void deleteReplacement(UUID id) {
        jdbc.sql("DELETE FROM plugin_mfa_replacement WHERE user_id=:id").param("id", id).update();
    }

    void revokeAuthenticationChallenges(UUID id) {
        jdbc.sql("DELETE FROM platform_login_challenge WHERE user_id=:id").param("id", id).update();
        jdbc.sql("DELETE FROM platform_reauthentication_challenge WHERE user_id=:id")
                .param("id", id)
                .update();
    }

    int purgeExpired(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 2_000));
        int replacements =
                jdbc.sql(
                                """
                        WITH expired AS (
                          SELECT user_id FROM plugin_mfa_replacement
                           WHERE expires_at<=CURRENT_TIMESTAMP
                           ORDER BY expires_at,user_id FOR UPDATE SKIP LOCKED LIMIT :limit
                        ) DELETE FROM plugin_mfa_replacement r USING expired e WHERE r.user_id=e.user_id
                        """)
                        .param("limit", limit)
                        .update();
        int setups =
                jdbc.sql(
                                """
                        WITH expired AS (
                          SELECT user_id FROM plugin_mfa_authenticator
                           WHERE confirmed=false AND expires_at<=CURRENT_TIMESTAMP
                           ORDER BY expires_at,user_id FOR UPDATE SKIP LOCKED LIMIT :limit
                        ) DELETE FROM plugin_mfa_authenticator a USING expired e WHERE a.user_id=e.user_id
                        """)
                        .param("limit", limit)
                        .update();
        return replacements + setups;
    }

    Optional<String> verifiedEmail(UUID id) {
        return jdbc.sql(
                        """
                        SELECT email FROM platform_account_email
                         WHERE user_id=:id AND email IS NOT NULL AND verified_at IS NOT NULL
                         FOR UPDATE
                        """)
                .param("id", id)
                .query(String.class)
                .optional();
    }

    PageResult<MfaResponse.Account> list(PageRequest page) {
        String search =
                "%"
                        + page.search()
                                .replace("\\", "\\\\")
                                .replace("%", "\\%")
                                .replace("_", "\\_")
                        + "%";
        String from =
                " FROM platform_second_factor_requirement q JOIN platform_user u ON u.id=q.user_id LEFT JOIN plugin_mfa_authenticator a ON a.user_id=u.id AND a.confirmed WHERE q.provider_id='mfa' AND u.deleted_at IS NULL AND (u.username ILIKE :search OR u.display_name ILIKE :search)";
        long count =
                jdbc.sql("SELECT count(*)" + from)
                        .param("search", search)
                        .query(Long.class)
                        .single();
        var items =
                jdbc.sql(
                                """
                        SELECT u.id,u.username,u.display_name,a.label,a.created_at,
                               (SELECT count(*) FROM plugin_mfa_recovery r WHERE r.user_id=u.id) AS remaining
                        """
                                        + from
                                        + " ORDER BY u.username,u.id LIMIT :limit OFFSET :offset")
                        .param("search", search)
                        .param("limit", page.pageSize())
                        .param("offset", page.offset())
                        .query(
                                (r, n) ->
                                        new MfaResponse.Account(
                                                r.getObject("id", UUID.class),
                                                r.getString("username"),
                                                r.getString("display_name"),
                                                r.getString("label"),
                                                r.getTimestamp("created_at") == null
                                                        ? null
                                                        : r.getTimestamp("created_at").toInstant(),
                                                r.getInt("remaining")))
                        .list();
        return new PageResult<>(items, count, page.page(), page.pageSize());
    }

    void recordReset(String actor, UUID target, String reason) {
        jdbc.sql(
                        "INSERT INTO plugin_mfa_reset_audit(id,actor,target_id,reason) VALUES(:id,:actor,:target,:reason)")
                .param("id", UUID.randomUUID())
                .param("actor", actor)
                .param("target", target)
                .param("reason", reason.strip())
                .update();
    }

    UUID lockUser(UUID id) {
        return jdbc.sql(
                        "SELECT id FROM platform_user WHERE id=:id AND deleted_at IS NULL FOR UPDATE")
                .param("id", id)
                .query(UUID.class)
                .optional()
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "userNotFound"));
    }
}
