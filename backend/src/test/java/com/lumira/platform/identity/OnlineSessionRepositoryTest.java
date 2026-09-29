package com.lumira.platform.identity;

import com.lumira.platform.common.PageRequest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class OnlineSessionRepositoryTest {
    JdbcTemplate admin, jdbc;
    OnlineSessionRepository repository;
    String schema;
    UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
    UUID aliceSession = UUID.randomUUID(), bobSession = UUID.randomUUID(), expiredSession = UUID.randomUUID();
    long aliceCreated;

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL"), user = System.getenv("LUMIRA_TEST_DB_USER"), password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_online_sessions_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(ds);
        repository = new OnlineSessionRepository(JdbcClient.create(ds));
        jdbc.execute("CREATE TABLE platform_user(id UUID PRIMARY KEY,username TEXT UNIQUE,display_name TEXT,enabled BOOLEAN NOT NULL)");
        jdbc.execute("CREATE TABLE spring_session(primary_id CHAR(36) PRIMARY KEY,session_id CHAR(36) NOT NULL,creation_time BIGINT NOT NULL,last_access_time BIGINT NOT NULL,max_inactive_interval INTEGER NOT NULL,expiry_time BIGINT NOT NULL,principal_name TEXT)");
        jdbc.update("INSERT INTO platform_user VALUES(?,'alice','Alice',true),(?,'bob','Bob',false)", alice, bob);
        long now = System.currentTimeMillis();
        aliceCreated = now - 10_000;
        insert(aliceSession, "alice", aliceCreated, now + 300_000);
        insert(bobSession, "bob", now - 20_000, now + 300_000);
        insert(expiredSession, "alice", now - 30_000, now - 1_000);
    }

    private void insert(UUID id, String username, long created, long expires) {
        jdbc.update("INSERT INTO spring_session VALUES(?,?, ?,?,?,?,?)", id, UUID.randomUUID(), created, created + 5_000, 1_800, expires, username);
    }

    @AfterEach
    void cleanup() { if (admin != null && schema != null) admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }

    @Test
    void listsOnlyActiveSessionsWithStableSearchAndPagination() {
        long now = System.currentTimeMillis();
        var page = repository.list(new PageRequest(1, 20, "ALICE"), now);
        assertEquals(1, page.size());
        assertEquals(aliceSession, page.getFirst().id());
        assertEquals(alice, page.getFirst().userId());
        assertEquals("Alice", page.getFirst().displayName());
        assertTrue(page.getFirst().enabled());
        assertEquals(1, repository.count(new PageRequest(1, 20, "alice"), now));
        assertEquals(2, repository.count(new PageRequest(1, 20, ""), now));
        assertEquals(Instant.ofEpochMilli(aliceCreated).getEpochSecond(), page.getFirst().createdAt().getEpochSecond());
    }

    @Test
    void revokesOneSessionOrAllSessionsForUser() {
        assertTrue(repository.revoke(aliceSession));
        assertFalse(repository.revoke(aliceSession));
        assertEquals(1, repository.revokeUser(bob));
        assertEquals(0, repository.revokeUser(bob));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE trim(primary_id) IN (?,?)", Integer.class, aliceSession.toString(), bobSession.toString()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class));
    }
}
