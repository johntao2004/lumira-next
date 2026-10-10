package com.lumira.platform.identity;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Shared database-backed challenge state for registration, OTP login and SMS recovery. */
@Repository
public class PublicAuthChallengeStore {
    public record Challenge(
            UUID id,
            PublicAuthPurpose purpose,
            String channel,
            String sessionDigest,
            String contactDigest,
            String contactCiphertext,
            String codeDigest,
            UUID userId,
            Long contactVersion,
            UUID primaryCredentialRevision,
            String state,
            int attempts,
            OffsetDateTime createdAt,
            OffsetDateTime resendAt,
            OffsetDateTime expiresAt,
            UUID proofId,
            OffsetDateTime proofExpiresAt,
            OffsetDateTime consumedAt) {

        public boolean proofActive(OffsetDateTime now) {
            return "VERIFIED".equals(state)
                    && proofId != null
                    && proofExpiresAt != null
                    && proofExpiresAt.isAfter(now);
        }
    }

    public record Completion(String fingerprint, UUID challengeId, UUID proofId, String receipt) {}

    private static final String SELECT_COLUMNS =
            """
            id,purpose,channel,session_digest,contact_digest,contact_ciphertext,code_digest,user_id,
            contact_version,primary_credential_revision,state,attempts,created_at,resend_at,expires_at,
            proof_id,proof_expires_at,consumed_at
            """;

    private final JdbcClient jdbc;

    public PublicAuthChallengeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Challenge create(
            UUID id,
            PublicAuthPurpose purpose,
            String channel,
            String sessionDigest,
            String contactDigest,
            String contactCiphertext,
            String codeDigest,
            UUID userId,
            Long contactVersion,
            UUID primaryCredentialRevision,
            int lifetimeSeconds,
            int cooldownSeconds) {
        lockSessionFlow(purpose, sessionDigest);
        jdbc.sql(
                        """
                        UPDATE platform_public_auth_challenge
                           SET state='SUPERSEDED',code_digest=NULL,proof_id=NULL,proof_expires_at=NULL
                         WHERE purpose=:purpose AND session_digest=:session
                           AND state IN ('PENDING','VERIFIED')
                        """)
                .param("purpose", purpose.name())
                .param("session", sessionDigest)
                .update();
        jdbc.sql(
                        """
                        INSERT INTO platform_public_auth_challenge
                            (id,purpose,channel,session_digest,contact_digest,contact_ciphertext,code_digest,
                             user_id,contact_version,primary_credential_revision,state,attempts,resend_at,expires_at)
                        VALUES (:id,:purpose,:channel,:session,:contactDigest,:contactCiphertext,:codeDigest,
                                :userId,:contactVersion,:credentialRevision,'PENDING',0,
                                now()+(:cooldown * interval '1 second'),now()+(:lifetime * interval '1 second'))
                        """)
                .param("id", id)
                .param("purpose", purpose.name())
                .param("channel", channel)
                .param("session", sessionDigest)
                .param("contactDigest", contactDigest)
                .param("contactCiphertext", contactCiphertext)
                .param("codeDigest", codeDigest)
                .param("userId", userId)
                .param("contactVersion", contactVersion)
                .param("credentialRevision", primaryCredentialRevision)
                .param("cooldown", cooldownSeconds)
                .param("lifetime", lifetimeSeconds)
                .update();
        return find(id, sessionDigest, purpose, false).orElseThrow();
    }

    /** Serialize sends/completion only within one purpose-bound anonymous flow. */
    public void lockSessionFlow(PublicAuthPurpose purpose, String sessionDigest) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:flow,0))")
                .param("flow", purpose.name() + ":" + sessionDigest)
                .query(
                        resultSet -> {
                            resultSet.next();
                            return 0;
                        });
    }

    public Optional<Challenge> find(
            UUID id, String sessionDigest, PublicAuthPurpose purpose, boolean lock) {
        return jdbc.sql(
                        "SELECT "
                                + SELECT_COLUMNS
                                + " FROM platform_public_auth_challenge"
                                + " WHERE id=:id AND session_digest=:session AND purpose=:purpose"
                                + (lock ? " FOR UPDATE" : ""))
                .param("id", id)
                .param("session", sessionDigest)
                .param("purpose", purpose.name())
                .query(PublicAuthChallengeStore::map)
                .optional();
    }

    public Optional<Challenge> latest(String sessionDigest, PublicAuthPurpose purpose) {
        return jdbc.sql(
                        "SELECT "
                                + SELECT_COLUMNS
                                + " FROM platform_public_auth_challenge"
                                + " WHERE session_digest=:session AND purpose=:purpose"
                                + " ORDER BY created_at DESC,id DESC LIMIT 1")
                .param("session", sessionDigest)
                .param("purpose", purpose.name())
                .query(PublicAuthChallengeStore::map)
                .optional();
    }

    public boolean failAttempt(UUID id, int maximumAttempts) {
        return jdbc.sql(
                        """
                        UPDATE platform_public_auth_challenge
                           SET attempts=least(:maximum,attempts+1),
                               state=CASE WHEN attempts+1>=:maximum THEN 'LOCKED' ELSE state END,
                               code_digest=CASE WHEN attempts+1>=:maximum THEN NULL ELSE code_digest END
                         WHERE id=:id AND state='PENDING' AND expires_at>now() AND attempts<:maximum
                         RETURNING state
                        """)
                .param("id", id)
                .param("maximum", maximumAttempts)
                .query(String.class)
                .optional()
                .isPresent();
    }

    public boolean verify(UUID id, UUID proofId, int maximumAttempts) {
        return jdbc.sql(
                        """
                        UPDATE platform_public_auth_challenge
                           SET state='VERIFIED',code_digest=NULL,proof_id=:proof,
                               proof_expires_at=least(expires_at,now()+interval '5 minutes')
                         WHERE id=:id AND state='PENDING' AND expires_at>now() AND attempts<:maximum
                         RETURNING proof_id
                        """)
                .param("id", id)
                .param("proof", proofId)
                .param("maximum", maximumAttempts)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    public boolean consume(UUID id, UUID proofId, String sessionDigest, PublicAuthPurpose purpose) {
        return jdbc.sql(
                        """
                        UPDATE platform_public_auth_challenge
                           SET state='CONSUMED',consumed_at=now(),code_digest=NULL
                         WHERE id=:id AND proof_id=:proof AND session_digest=:session AND purpose=:purpose
                           AND state='VERIFIED' AND proof_expires_at>now() AND expires_at>now()
                         RETURNING id
                        """)
                .param("id", id)
                .param("proof", proofId)
                .param("session", sessionDigest)
                .param("purpose", purpose.name())
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    public Optional<Challenge> verifiedByProof(
            UUID proofId, String sessionDigest, PublicAuthPurpose purpose, boolean lock) {
        return jdbc.sql(
                        "SELECT "
                                + SELECT_COLUMNS
                                + " FROM platform_public_auth_challenge"
                                + " WHERE proof_id=:proof AND session_digest=:session AND purpose=:purpose"
                                + (lock ? " FOR UPDATE" : ""))
                .param("proof", proofId)
                .param("session", sessionDigest)
                .param("purpose", purpose.name())
                .query(PublicAuthChallengeStore::map)
                .optional();
    }

    public boolean reserveCompletion(
            UUID requestId,
            String sessionDigest,
            UUID challengeId,
            UUID proofId,
            String fingerprint) {
        return jdbc.sql(
                        """
                        INSERT INTO platform_registration_completion
                            (request_id,session_digest,challenge_id,proof_id,request_fingerprint)
                        VALUES (:request,:session,:challenge,:proof,:fingerprint)
                        ON CONFLICT(request_id) DO NOTHING
                        RETURNING request_id
                        """)
                .param("request", requestId)
                .param("session", sessionDigest)
                .param("challenge", challengeId)
                .param("proof", proofId)
                .param("fingerprint", fingerprint)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    public Optional<Completion> completion(UUID requestId) {
        return jdbc.sql(
                        "SELECT request_fingerprint,challenge_id,proof_id,receipt::text FROM platform_registration_completion WHERE request_id=:id")
                .param("id", requestId)
                .query(
                        (rs, row) ->
                                new Completion(
                                        rs.getString("request_fingerprint"),
                                        rs.getObject("challenge_id", UUID.class),
                                        rs.getObject("proof_id", UUID.class),
                                        rs.getString("receipt")))
                .optional();
    }

    public Optional<Completion> completionForProof(UUID proofId, String sessionDigest) {
        return jdbc.sql(
                        "SELECT request_fingerprint,challenge_id,proof_id,receipt::text FROM platform_registration_completion WHERE proof_id=:proof AND session_digest=:session AND completed_at IS NOT NULL ORDER BY completed_at DESC LIMIT 1")
                .param("proof", proofId)
                .param("session", sessionDigest)
                .query(
                        (rs, row) ->
                                new Completion(
                                        rs.getString("request_fingerprint"),
                                        rs.getObject("challenge_id", UUID.class),
                                        rs.getObject("proof_id", UUID.class),
                                        rs.getString("receipt")))
                .optional();
    }

    public boolean finishCompletion(UUID requestId, UUID accountId, String receiptJson) {
        return jdbc.sql(
                        "UPDATE platform_registration_completion SET account_id=:account,receipt=CAST(:receipt AS jsonb),completed_at=now() WHERE request_id=:request AND account_id IS NULL RETURNING request_id")
                .param("request", requestId)
                .param("account", accountId)
                .param("receipt", receiptJson)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    private static Challenge map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        var purpose = PublicAuthPurpose.valueOf(rs.getString("purpose"));
        return new Challenge(
                rs.getObject("id", UUID.class),
                purpose,
                rs.getString("channel"),
                rs.getString("session_digest"),
                rs.getString("contact_digest"),
                rs.getString("contact_ciphertext"),
                rs.getString("code_digest"),
                rs.getObject("user_id", UUID.class),
                rs.getObject("contact_version", Long.class),
                rs.getObject("primary_credential_revision", UUID.class),
                rs.getString("state"),
                rs.getInt("attempts"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("resend_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("proof_id", UUID.class),
                rs.getObject("proof_expires_at", OffsetDateTime.class),
                rs.getObject("consumed_at", OffsetDateTime.class));
    }
}
