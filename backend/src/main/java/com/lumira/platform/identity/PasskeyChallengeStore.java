package com.lumira.platform.identity;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Single-use WebAuthn challenges. The database is the source of truth so a
 * multi-instance deployment does not depend on a local cache or sticky
 * session.
 */
@Repository
public class PasskeyChallengeStore {
    public record Challenge(UUID id, String kind, byte[] challenge, UUID userId,
                            byte[] userHandle, String sessionId, OffsetDateTime expiresAt) {}

    private final JdbcClient jdbc;

    public PasskeyChallengeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Challenge create(String kind, byte[] challenge, UUID userId, byte[] userHandle,
                            String sessionId, int ttlSeconds) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO platform_passkey_challenge
                    (id,kind,challenge,user_id,user_handle,session_id,expires_at)
                VALUES (:id,:kind,:challenge,:userId,:userHandle,:sessionId,
                        now() + (:ttlSeconds * interval '1 second'))
                """)
            .param("id", id)
            .param("kind", kind)
            .param("challenge", challenge)
            .param("userId", userId)
            .param("userHandle", userHandle)
            .param("sessionId", sessionId)
            .param("ttlSeconds", ttlSeconds)
            .update();
        return jdbc.sql("""
                SELECT id,kind,challenge,user_id,user_handle,session_id,expires_at
                FROM platform_passkey_challenge WHERE id=:id
                """)
            .param("id", id).query(this::map).single();
    }

    /** Atomically consumes an unexpired challenge and prevents replay. */
    public java.util.Optional<Challenge> consume(UUID id, String kind, String sessionId) {
        return jdbc.sql("""
                UPDATE platform_passkey_challenge
                   SET consumed_at=now()
                 WHERE id=:id AND kind=:kind AND session_id=:sessionId
                   AND consumed_at IS NULL AND expires_at>now()
                RETURNING id,kind,challenge,user_id,user_handle,session_id,expires_at
                """)
            .param("id", id)
            .param("kind", kind)
            .param("sessionId", sessionId)
            .query(this::map).optional();
    }

    public int purgeExpired(int limit) {
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("invalid challenge purge limit");
        return jdbc.sql("""
                WITH expired AS (
                  SELECT id FROM platform_passkey_challenge
                   WHERE expires_at<=now() OR consumed_at IS NOT NULL
                   ORDER BY expires_at,id FOR UPDATE SKIP LOCKED LIMIT :limit
                ) DELETE FROM platform_passkey_challenge c USING expired e WHERE c.id=e.id
                """).param("limit", limit).update();
    }

    private Challenge map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Challenge(
            rs.getObject("id", UUID.class), rs.getString("kind"), rs.getBytes("challenge"),
            rs.getObject("user_id", UUID.class), rs.getBytes("user_handle"),
            rs.getString("session_id"), rs.getObject("expires_at", OffsetDateTime.class));
    }
}
