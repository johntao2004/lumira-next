package com.lumira.platform.monitoring;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-only aggregate counters for the platform operations dashboard. */
@Repository
public class MonitoringRepository {
    public record Summary(long totalUsers, long activeUsers, long activeSessions,
                          long enabledModules, long totalModules, long pendingOutbox,
                          long failedOutbox, long deadLetterOutbox, long pendingMail,
                          long failedMail, long pendingSms, long failedSms, long unreadNotifications, long auditEvents24h,
                          long processingOutbox, long eventsCreatedLast5m, long eventsDeliveredLast5m,
                          OffsetDateTime measuredAt) {}
    public record FlowEvent(String sourceType, String eventType, String status, int attempts, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;

    public MonitoringRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Summary summary(long now) {
        return jdbc.sql("""
                WITH outbox_metrics AS (
                  SELECT
                    count(*) FILTER (WHERE status='PENDING') AS pending_outbox,
                    count(*) FILTER (WHERE status='PROCESSING') AS processing_outbox,
                    count(*) FILTER (WHERE status='FAILED') AS failed_outbox,
                    count(*) FILTER (WHERE status='DEAD_LETTER') AS dead_letter_outbox,
                    count(*) FILTER (WHERE created_at>=now()-interval '5 minutes') AS events_created_last_5m,
                    count(*) FILTER (WHERE status='DELIVERED' AND delivered_at>=now()-interval '5 minutes') AS events_delivered_last_5m
                  FROM platform_event_outbox
                ), mail_metrics AS (
                  SELECT
                    count(*) FILTER (WHERE state IN ('PENDING','LEASED','SENDING')) AS pending_mail,
                    count(*) FILTER (WHERE state IN ('FAILED','UNCERTAIN')) AS failed_mail
                  FROM platform_mail_delivery
                ), sms_metrics AS (
                  SELECT
                    count(*) FILTER (WHERE state IN ('PENDING','LEASED','SENDING')) AS pending_sms,
                    count(*) FILTER (WHERE state IN ('FAILED','UNCERTAIN')) AS failed_sms
                  FROM platform_sms_delivery
                )
                SELECT
                  (SELECT count(*) FROM platform_user) AS total_users,
                  (SELECT count(*) FROM platform_user WHERE enabled) AS active_users,
                  (SELECT count(*) FROM spring_session WHERE expiry_time>:session_now) AS active_sessions,
                  (SELECT count(*) FROM platform_module_state WHERE enabled) AS enabled_modules,
                  (SELECT count(*) FROM platform_module_state) AS total_modules,
                  outbox_metrics.pending_outbox,
                  outbox_metrics.processing_outbox,
                  outbox_metrics.failed_outbox,
                  outbox_metrics.dead_letter_outbox,
                  outbox_metrics.events_created_last_5m,
                  outbox_metrics.events_delivered_last_5m,
                  mail_metrics.pending_mail,
                  mail_metrics.failed_mail,
                  sms_metrics.pending_sms,
                  sms_metrics.failed_sms,
                  (SELECT count(*) FROM platform_notification WHERE read_at IS NULL AND (expires_at IS NULL OR expires_at>now())) AS unread_notifications,
                  (SELECT count(*) FROM platform_audit WHERE created_at>=now()-interval '24 hours') AS audit_events_24h,
                  now() AS measured_at
                FROM outbox_metrics, mail_metrics, sms_metrics
                """)
            .param("session_now", now)
            .query((rs, ignored) -> new Summary(
                rs.getLong("total_users"), rs.getLong("active_users"), rs.getLong("active_sessions"),
                rs.getLong("enabled_modules"), rs.getLong("total_modules"), rs.getLong("pending_outbox"),
                rs.getLong("failed_outbox"), rs.getLong("dead_letter_outbox"), rs.getLong("pending_mail"),
                rs.getLong("failed_mail"), rs.getLong("pending_sms"), rs.getLong("failed_sms"), rs.getLong("unread_notifications"), rs.getLong("audit_events_24h"),
                rs.getLong("processing_outbox"), rs.getLong("events_created_last_5m"), rs.getLong("events_delivered_last_5m"),
                rs.getObject("measured_at", OffsetDateTime.class))).single();
    }

    public java.util.List<FlowEvent> recentFlow() {
        return jdbc.sql("""
                SELECT source_type, event_type, status, attempts, created_at
                FROM platform_event_outbox
                ORDER BY created_at DESC, id DESC
                LIMIT 8
                """)
            .query(FlowEvent.class)
            .list();
    }
}
