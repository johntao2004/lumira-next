package com.lumira.platform.ai;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PendingAiActionStore {
    public enum Kind {
        CREATE_USER,
        UPDATE_USER,
        DELETE_USER
    }

    public record PendingAction(
            UUID id,
            String actor,
            Kind kind,
            UUID userId,
            long version,
            String username,
            String displayName,
            Boolean enabled,
            Target target,
            Instant expiresAt) {}

    /** Server-authorized target captured before an update or delete proposal is created. */
    public record Target(
            UUID id, String username, String displayName, boolean enabled, long version) {}

    public record Proposal(
            UUID id,
            Kind kind,
            String username,
            String displayName,
            Boolean enabled,
            Instant expiresAt,
            Target target) {
        public Proposal(
                UUID id,
                Kind kind,
                String username,
                String displayName,
                Boolean enabled,
                Instant expiresAt) {
            this(id, kind, username, displayName, enabled, expiresAt, null);
        }
    }

    private final JdbcClient jdbc;

    public PendingAiActionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Proposal create(
            String actor,
            Kind kind,
            UUID userId,
            long version,
            String username,
            String displayName,
            Boolean enabled) {
        return create(actor, kind, userId, version, username, displayName, enabled, null);
    }

    public Proposal create(
            String actor,
            Kind kind,
            UUID userId,
            long version,
            String username,
            String displayName,
            Boolean enabled,
            Target target) {
        purgeExpiredRows();
        if (kind == Kind.CREATE_USER && target != null
                || kind != Kind.CREATE_USER
                        && (target == null
                                || !target.id().equals(userId)
                                || target.version() != version)) {
            throw new IllegalArgumentException("Invalid AI action target snapshot");
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime expiresAt =
                jdbc.sql(
                                """
            INSERT INTO platform_ai_pending_action(
                id,actor,kind,user_id,version,username,display_name,enabled,
                target_username,target_display_name,target_enabled,expires_at)
            VALUES(:id,:actor,:kind,:userId,:version,:username,:displayName,:enabled,
                :targetUsername,:targetDisplayName,:targetEnabled,clock_timestamp()+interval '3 minutes')
            RETURNING expires_at
            """)
                        .param("id", id)
                        .param("actor", actor)
                        .param("kind", kind.name())
                        .param("userId", userId)
                        .param("version", version)
                        .param("username", username)
                        .param("displayName", displayName)
                        .param("enabled", enabled)
                        .param("targetUsername", target == null ? null : target.username())
                        .param("targetDisplayName", target == null ? null : target.displayName())
                        .param("targetEnabled", target == null ? null : target.enabled())
                        .query((rs, row) -> rs.getObject("expires_at", OffsetDateTime.class))
                        .single();
        Instant expiry = expiresAt.toInstant();
        return new Proposal(id, kind, username, displayName, enabled, expiry, target);
    }

    public PendingAction findPending(UUID id, String actor) {
        var action =
                jdbc.sql(
                                """
            SELECT id,actor,kind,user_id,version,username,display_name,enabled,
                   target_username,target_display_name,target_enabled,expires_at
              FROM platform_ai_pending_action
             WHERE id=:id AND actor=:actor AND expires_at>clock_timestamp()
            """)
                        .param("id", id)
                        .param("actor", actor)
                        .query(
                                (rs, row) ->
                                        new PendingAction(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("actor"),
                                                Kind.valueOf(rs.getString("kind")),
                                                rs.getObject("user_id", UUID.class),
                                                rs.getLong("version"),
                                                rs.getString("username"),
                                                rs.getString("display_name"),
                                                (Boolean) rs.getObject("enabled"),
                                                target(rs),
                                                rs.getObject("expires_at", OffsetDateTime.class)
                                                        .toInstant()))
                        .optional();
        if (action.isPresent()) return action.get();
        throw new ResponseStatusException(HttpStatus.GONE, "aiActionExpired");
    }

    /** Consume only after the requested operation and target scope have been authorized. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consume(UUID id, String actor) {
        boolean consumed =
                jdbc.sql(
                                "DELETE FROM platform_ai_pending_action WHERE id=:id AND actor=:actor AND expires_at>clock_timestamp() RETURNING id")
                        .param("id", id)
                        .param("actor", actor)
                        .query(UUID.class)
                        .optional()
                        .isPresent();
        if (!consumed) throw new ResponseStatusException(HttpStatus.GONE, "aiActionExpired");
    }

    private static Target target(java.sql.ResultSet rs) throws java.sql.SQLException {
        String username = rs.getString("target_username");
        String displayName = rs.getString("target_display_name");
        Boolean enabled = (Boolean) rs.getObject("target_enabled");
        if (username == null && displayName == null && enabled == null) return null;
        if (username == null || displayName == null || enabled == null) return null;
        return new Target(
                rs.getObject("user_id", UUID.class),
                username,
                displayName,
                enabled,
                rs.getLong("version"));
    }

    public void cancel(UUID id, String actor) {
        jdbc.sql("DELETE FROM platform_ai_pending_action WHERE id=:id AND actor=:actor")
                .param("id", id)
                .param("actor", actor)
                .update();
    }

    public void cleanupExpired() {
        purgeExpiredRows();
    }

    private int purgeExpiredRows() {
        return jdbc.sql(
                        """
            WITH expired AS (
                SELECT id FROM platform_ai_pending_action
                 WHERE expires_at<=clock_timestamp()
                 ORDER BY expires_at,id LIMIT 500 FOR UPDATE SKIP LOCKED
            )
            DELETE FROM platform_ai_pending_action a USING expired e WHERE a.id=e.id
            """)
                .update();
    }
}
