package com.lumira.platform.identity;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Persistent bootstrap-password requirement, independent of one-time administrative resets. */
@Service
public class BootstrapPasswordChangeRequirementService {
    public record Requirement(UUID credentialRevision, Instant completedAt) {
        public boolean activeFor(UUID currentRevision) {
            return completedAt == null && credentialRevision.equals(currentRevision);
        }
    }

    private final JdbcClient jdbc;

    public BootstrapPasswordChangeRequirementService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Call from the caller's transaction after acquiring the user row lock. */
    Requirement lockRequirement(UUID userId) {
        return jdbc.sql(
                        "SELECT primary_credential_revision,completed_at FROM platform_bootstrap_password_change_requirement WHERE user_id=:id FOR UPDATE")
                .param("id", userId)
                .query(
                        (rs, row) ->
                                new Requirement(
                                        rs.getObject("primary_credential_revision", UUID.class),
                                        rs.getTimestamp("completed_at") == null
                                                ? null
                                                : rs.getTimestamp("completed_at").toInstant()))
                .optional()
                .orElse(null);
    }

    void complete(UUID userId) {
        jdbc.sql(
                        "UPDATE platform_bootstrap_password_change_requirement SET completed_at=GREATEST(COALESCE(completed_at,created_at),CURRENT_TIMESTAMP) WHERE user_id=:id")
                .param("id", userId)
                .update();
    }

    void bindCurrentRevision(UUID userId, UUID revision) {
        jdbc.sql(
                        "UPDATE platform_bootstrap_password_change_requirement SET primary_credential_revision=:revision WHERE user_id=:id AND completed_at IS NULL")
                .param("id", userId)
                .param("revision", revision)
                .update();
    }
}
