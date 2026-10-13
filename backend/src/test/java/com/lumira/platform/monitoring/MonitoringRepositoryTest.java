package com.lumira.platform.monitoring;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class MonitoringRepositoryTest {
    JdbcTemplate admin, jdbc;
    String schema;

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_monitoring_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE platform_user(id UUID PRIMARY KEY,enabled BOOLEAN NOT NULL)");
        jdbc.execute(
                "CREATE TABLE spring_session(primary_id CHAR(36) PRIMARY KEY,expiry_time BIGINT NOT"
                        + " NULL)");
        jdbc.execute(
                "CREATE TABLE platform_module_state(id UUID PRIMARY KEY,enabled BOOLEAN NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_event_outbox(id UUID PRIMARY KEY,status TEXT NOT"
                        + " NULL,source_type TEXT NOT NULL DEFAULT 'test',event_type TEXT NOT NULL"
                        + " DEFAULT 'test.event',attempts INT NOT NULL DEFAULT 0,last_error"
                        + " TEXT,available_at TIMESTAMPTZ NOT NULL DEFAULT now(),lease_until"
                        + " TIMESTAMPTZ,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),delivered_at"
                        + " TIMESTAMPTZ)");
        jdbc.execute(
                "CREATE TABLE platform_mail_delivery(id UUID PRIMARY KEY,state TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE platform_sms_delivery(id UUID PRIMARY KEY,state TEXT NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_notification(id UUID PRIMARY KEY,read_at"
                        + " TIMESTAMPTZ,expires_at TIMESTAMPTZ)");
        jdbc.execute(
                "CREATE TABLE platform_audit(id UUID PRIMARY KEY,created_at TIMESTAMPTZ NOT NULL)");
        jdbc.update(
                "INSERT INTO platform_user VALUES(?,true),(?,false)",
                UUID.randomUUID(),
                UUID.randomUUID());
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO spring_session VALUES(?,?)", UUID.randomUUID(), now + 60_000);
        jdbc.update(
                "INSERT INTO platform_module_state VALUES(?,true),(?,false)",
                UUID.randomUUID(),
                UUID.randomUUID());
        jdbc.update(
                "INSERT INTO platform_event_outbox(id,status) VALUES(?, 'PENDING'),(?,"
                        + " 'FAILED'),(?, 'DEAD_LETTER'),(?, 'PROCESSING')",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID());
        jdbc.update(
                "INSERT INTO platform_event_outbox(id,status,delivered_at) VALUES(?, 'DELIVERED',"
                        + " now())",
                UUID.randomUUID());
        jdbc.update(
                "INSERT INTO platform_event_outbox(id,status,created_at) VALUES(?, 'PENDING',"
                        + " now()-interval '1 hour')",
                UUID.randomUUID());
        jdbc.update(
                "UPDATE platform_event_outbox SET last_error='previous attempt failed' WHERE"
                        + " status='PENDING' AND created_at>now()-interval '5 minutes'");
        jdbc.update(
                "INSERT INTO platform_event_outbox(id,status,created_at,last_error) VALUES(?,"
                        + " 'PENDING', now(), '   ')",
                UUID.randomUUID());
        jdbc.update(
                "UPDATE platform_event_outbox SET available_at=now()+interval '1 hour' WHERE"
                        + " status='PENDING' AND created_at<now()-interval '5 minutes'");
        jdbc.update(
                "UPDATE platform_event_outbox SET lease_until=now()-interval '1 minute' WHERE"
                        + " status='PROCESSING'");
        jdbc.update(
                "INSERT INTO platform_mail_delivery VALUES(?, 'SENDING'),(?, 'UNCERTAIN')",
                UUID.randomUUID(),
                UUID.randomUUID());
        jdbc.update(
                "INSERT INTO platform_sms_delivery VALUES(?, 'PENDING'),(?, 'FAILED')",
                UUID.randomUUID(),
                UUID.randomUUID());
        jdbc.update(
                "INSERT INTO platform_notification VALUES(?,NULL,NULL),(?,now()-interval '1"
                        + " minute',now()-interval '1 second')",
                UUID.randomUUID(),
                UUID.randomUUID());
        jdbc.update(
                "INSERT INTO platform_audit VALUES(?,now()),(?,now()-interval '2 days')",
                UUID.randomUUID(),
                UUID.randomUUID());
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void aggregatesOperationalCountersFromOneSnapshot() {
        var summary =
                new MonitoringRepository(JdbcClient.create(jdbc.getDataSource()))
                        .summary(System.currentTimeMillis());
        assertEquals(2, summary.totalUsers());
        assertEquals(1, summary.activeUsers());
        assertEquals(1, summary.activeSessions());
        assertEquals(1, summary.enabledModules());
        assertEquals(2, summary.totalModules());
        assertEquals(3, summary.pendingOutbox());
        assertEquals(1, summary.processingOutbox());
        assertEquals(6, summary.eventsCreatedLast5m());
        assertEquals(1, summary.eventsDeliveredLast5m());
        assertEquals(1, summary.retryPendingOutbox());
        assertEquals(2, summary.overdueOutbox());
        assertEquals(1, summary.failedOutbox());
        assertEquals(1, summary.deadLetterOutbox());
        assertEquals(1, summary.pendingMail());
        assertEquals(1, summary.failedMail());
        assertEquals(1, summary.pendingSms());
        assertEquals(1, summary.failedSms());
        assertEquals(1, summary.unreadNotifications());
        assertEquals(1, summary.auditEvents24h());
        assertNotNull(summary.measuredAt());
    }

    @Test
    void recentFlowDoesNotExposePayloadOrLastError() {
        var rows = new MonitoringRepository(JdbcClient.create(jdbc.getDataSource())).recentFlow();
        assertFalse(rows.isEmpty());
        assertEquals("test", rows.getFirst().sourceType());
        assertEquals("test.event", rows.getFirst().eventType());
        assertFalse(rows.getFirst().toString().contains("previous attempt failed"));
    }
}
