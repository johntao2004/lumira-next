package com.lumira.platform.monitoring;

import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class MonitoringRepositoryTest {
    JdbcTemplate admin, jdbc;
    String schema;

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL"), user = System.getenv("LUMIRA_TEST_DB_USER"), password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_monitoring_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE platform_user(id UUID PRIMARY KEY,enabled BOOLEAN NOT NULL)");
        jdbc.execute("CREATE TABLE spring_session(primary_id CHAR(36) PRIMARY KEY,expiry_time BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE platform_module_state(id UUID PRIMARY KEY,enabled BOOLEAN NOT NULL)");
        jdbc.execute("CREATE TABLE platform_event_outbox(id UUID PRIMARY KEY,status TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE platform_mail_delivery(id UUID PRIMARY KEY,state TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE platform_sms_delivery(id UUID PRIMARY KEY,state TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE platform_notification(id UUID PRIMARY KEY,read_at TIMESTAMPTZ,expires_at TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE platform_audit(id UUID PRIMARY KEY,created_at TIMESTAMPTZ NOT NULL)");
        jdbc.update("INSERT INTO platform_user VALUES(?,true),(?,false)", UUID.randomUUID(), UUID.randomUUID());
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO spring_session VALUES(?,?)", UUID.randomUUID(), now + 60_000);
        jdbc.update("INSERT INTO platform_module_state VALUES(?,true),(?,false)", UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO platform_event_outbox VALUES(?, 'PENDING'),(?, 'FAILED'),(?, 'DEAD_LETTER')", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO platform_mail_delivery VALUES(?, 'SENDING'),(?, 'UNCERTAIN')", UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO platform_sms_delivery VALUES(?, 'PENDING'),(?, 'FAILED')", UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO platform_notification VALUES(?,NULL,NULL),(?,now()-interval '1 minute',now()-interval '1 second')", UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO platform_audit VALUES(?,now()),(?,now()-interval '2 days')", UUID.randomUUID(), UUID.randomUUID());
    }

    @AfterEach
    void cleanup() { if (admin != null && schema != null) admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }

    @Test
    void aggregatesOperationalCountersFromOneSnapshot() {
        var summary = new MonitoringRepository(JdbcClient.create(jdbc.getDataSource())).summary(System.currentTimeMillis());
        assertEquals(2, summary.totalUsers());
        assertEquals(1, summary.activeUsers());
        assertEquals(1, summary.activeSessions());
        assertEquals(1, summary.enabledModules());
        assertEquals(2, summary.totalModules());
        assertEquals(1, summary.pendingOutbox());
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
}
