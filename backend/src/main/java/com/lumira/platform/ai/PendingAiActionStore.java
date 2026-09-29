package com.lumira.platform.ai;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PendingAiActionStore {
    public enum Kind { CREATE_USER, UPDATE_USER, DELETE_USER }
    public record PendingAction(UUID id, String actor, Kind kind, UUID userId, long version,
                                String username, String displayName, Boolean enabled, Instant expiresAt) {}
    public record Proposal(UUID id, Kind kind, String username, String displayName, Boolean enabled, Instant expiresAt) {}

    private final JdbcClient jdbc;

    public PendingAiActionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Proposal create(String actor, Kind kind, UUID userId, long version,
                           String username, String displayName, Boolean enabled) {
        purgeExpiredRows();
        UUID id = UUID.randomUUID();
        OffsetDateTime expiresAt = jdbc.sql("""
            INSERT INTO platform_ai_pending_action(id,actor,kind,user_id,version,username,display_name,enabled,expires_at)
            VALUES(:id,:actor,:kind,:userId,:version,:username,:displayName,:enabled,clock_timestamp()+interval '3 minutes')
            RETURNING expires_at
            """).param("id", id).param("actor", actor).param("kind", kind.name()).param("userId", userId)
            .param("version", version).param("username", username).param("displayName", displayName)
            .param("enabled", enabled).query((rs, row) -> rs.getObject("expires_at", OffsetDateTime.class)).single();
        Instant expiry = expiresAt.toInstant();
        return new Proposal(id, kind, username, displayName, enabled, expiry);
    }

    public PendingAction take(UUID id, String actor) {
        var action = jdbc.sql("""
            DELETE FROM platform_ai_pending_action
             WHERE id=:id AND actor=:actor AND expires_at>clock_timestamp()
            RETURNING id,actor,kind,user_id,version,username,display_name,enabled,expires_at
            """).param("id", id).param("actor", actor).query((rs, row) -> new PendingAction(
                rs.getObject("id", UUID.class), rs.getString("actor"), Kind.valueOf(rs.getString("kind")),
                rs.getObject("user_id", UUID.class), rs.getLong("version"), rs.getString("username"),
                rs.getString("display_name"), (Boolean) rs.getObject("enabled"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant())).optional();
        if (action.isPresent()) return action.get();
        jdbc.sql("DELETE FROM platform_ai_pending_action WHERE id=:id AND expires_at<=clock_timestamp()")
            .param("id", id).update();
        throw new ResponseStatusException(HttpStatus.GONE, "aiActionExpired");
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void cleanupExpired() {
        purgeExpiredRows();
    }

    private int purgeExpiredRows() {
        return jdbc.sql("""
            WITH expired AS (
                SELECT id FROM platform_ai_pending_action
                 WHERE expires_at<=clock_timestamp()
                 ORDER BY expires_at,id LIMIT 500 FOR UPDATE SKIP LOCKED
            )
            DELETE FROM platform_ai_pending_action a USING expired e WHERE a.id=e.id
            """).update();
    }
}
