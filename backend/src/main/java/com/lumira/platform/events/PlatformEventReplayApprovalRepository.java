package com.lumira.platform.events;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Durable operator approval history for dead-letter recovery. */
@Repository
public class PlatformEventReplayApprovalRepository {
    private final JdbcClient jdbc;

    public PlatformEventReplayApprovalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(UUID eventId, String actor, String reason) {
        append(UUID.randomUUID(), eventId, actor, reason);
    }

    public void append(UUID auditOperationId, UUID eventId, String actor, String reason) {
        jdbc.sql(
                        """
                        INSERT INTO platform_event_replay_approval(id,event_id,approved_by,reason)
                        VALUES(:id,:event,:actor,:reason)
                        """)
                .param("id", auditOperationId)
                .param("event", eventId)
                .param("actor", actor)
                .param("reason", reason.strip())
                .update();
    }
}
