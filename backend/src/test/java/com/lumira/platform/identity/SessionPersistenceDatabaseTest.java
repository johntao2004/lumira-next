package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.support.TransactionTemplate;

/** Real Spring Session SQL against B52-derived tables in a disposable schema. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class SessionPersistenceDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private org.springframework.session.SessionRepository<org.springframework.session.Session>
            repository;
    private String schema;
    private Instant initial;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void createSchema() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_session_touch_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(source);
        jdbc.execute(TestSchema.table("spring_session"));
        jdbc.execute(TestSchema.table("spring_session_attributes"));
        TestSchema.foreignKeys("spring_session_attributes").forEach(jdbc::execute);
        var jdbcRepository =
                new JdbcIndexedSessionRepository(
                        jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)));
        new SessionPersistenceConfiguration()
                .sessionTouchCustomizer(1000)
                .customize(jdbcRepository);
        repository = SessionPersistenceConfiguration.wrap(jdbcRepository, 1000);
        initial = Instant.now().minusSeconds(10);
    }

    @AfterEach
    void removeOwnedSchema() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private org.springframework.session.Session create() {
        var session = repository.createSession();
        session.setMaxInactiveInterval(Duration.ofMinutes(30));
        session.setLastAccessedTime(initial);
        repository.save(session);
        return session;
    }

    private long persistedAccess(String id) {
        return jdbc.queryForObject(
                "SELECT last_access_time FROM spring_session WHERE session_id=?", Long.class, id);
    }

    @Test
    void repeatedTouchesInsideWindowDoNotWriteMetadataButAttributesPersistImmediately() {
        var session = create();
        String xmin =
                jdbc.queryForObject(
                        "SELECT xmin::text FROM spring_session WHERE session_id=?",
                        String.class,
                        session.getId());
        session.setLastAccessedTime(initial.plusMillis(200));
        session.setAttribute("fixture", "new value");
        repository.save(session);
        assertEquals(initial.toEpochMilli(), persistedAccess(session.getId()));
        assertEquals(
                xmin,
                jdbc.queryForObject(
                        "SELECT xmin::text FROM spring_session WHERE session_id=?",
                        String.class,
                        session.getId()));
        assertEquals("new value", repository.findById(session.getId()).getAttribute("fixture"));
        session.setLastAccessedTime(initial.plusMillis(400));
        session.setAttribute("fixture", "updated value");
        repository.save(session);
        assertEquals("updated value", repository.findById(session.getId()).getAttribute("fixture"));
        session.setLastAccessedTime(initial.plusMillis(600));
        session.removeAttribute("fixture");
        repository.save(session);
        assertNull(repository.findById(session.getId()).getAttribute("fixture"));
        assertEquals(
                xmin,
                jdbc.queryForObject(
                        "SELECT xmin::text FROM spring_session WHERE session_id=?",
                        String.class,
                        session.getId()));
        session.setLastAccessedTime(initial.plusMillis(1000));
        repository.save(session);
        assertEquals(initial.plusMillis(1000).toEpochMilli(), persistedAccess(session.getId()));
    }

    @Test
    void principalAndIdRotationPersistWithoutWaitingForTheTouchWindow() {
        var session = create();
        String oldId = session.getId();
        session.setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "fixture-user");
        session.setLastAccessedTime(initial.plusMillis(100));
        session.changeSessionId();
        repository.save(session);
        assertNull(repository.findById(oldId));
        assertNotNull(repository.findById(session.getId()));
        assertEquals(
                "fixture-user",
                jdbc.queryForObject(
                        "SELECT principal_name FROM spring_session WHERE session_id=?",
                        String.class,
                        session.getId()));
    }

    @Test
    void shorteningTimeoutAppliesImmediatelyAndOlderTouchesCannotRegressActivity() {
        var session = create();
        var stale = repository.findById(session.getId());
        session.setLastAccessedTime(initial.plusSeconds(2));
        repository.save(session);
        stale.setLastAccessedTime(initial.plusMillis(100));
        repository.save(stale);
        assertEquals(initial.plusSeconds(2).toEpochMilli(), persistedAccess(session.getId()));
        stale.setMaxInactiveInterval(Duration.ofMinutes(5));
        repository.save(stale);
        assertEquals(
                initial.plusSeconds(302).toEpochMilli(),
                jdbc.queryForObject(
                        "SELECT expiry_time FROM spring_session WHERE session_id=?",
                        Long.class,
                        session.getId()));
    }

    @Test
    void revocationCannotBeUndoneBySavingAnOlderRequest() {
        var session = create();
        var stale = repository.findById(session.getId());
        repository.deleteById(session.getId());
        stale.setLastAccessedTime(initial.plusSeconds(2));
        repository.save(stale);
        assertNull(repository.findById(session.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM spring_session_attributes", Integer.class));
    }

    @Test
    void nonExpiringAndVeryShortSessionsPreserveTheirExpiryRules() {
        var session = create();
        session.setMaxInactiveInterval(Duration.ofSeconds(-1));
        repository.save(session);
        assertEquals(
                -1,
                jdbc.queryForObject(
                        "SELECT max_inactive_interval FROM spring_session WHERE session_id=?",
                        Integer.class,
                        session.getId()));
        session.setMaxInactiveInterval(Duration.ofSeconds(1));
        session.setLastAccessedTime(Instant.now());
        repository.save(session);
        Instant next = session.getLastAccessedTime().plusMillis(50);
        session.setLastAccessedTime(next);
        repository.save(session);
        assertEquals(next.toEpochMilli(), persistedAccess(session.getId()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void independentNodesUseThePersistedWindowRatherThanALocalCache() {
        var first = create();
        var otherJdbc =
                new JdbcIndexedSessionRepository(
                        jdbc,
                        new TransactionTemplate(
                                new DataSourceTransactionManager(jdbc.getDataSource())));
        new SessionPersistenceConfiguration().sessionTouchCustomizer(1000).customize(otherJdbc);
        var other = SessionPersistenceConfiguration.wrap(otherJdbc, 1000);
        var second = other.findById(first.getId());
        first.setLastAccessedTime(initial.plusMillis(1000));
        repository.save(first);
        String xmin =
                jdbc.queryForObject(
                        "SELECT xmin::text FROM spring_session WHERE session_id=?",
                        String.class,
                        first.getId());
        second.setLastAccessedTime(initial.plusMillis(1100));
        other.save(second);
        assertEquals(initial.plusMillis(1000).toEpochMilli(), persistedAccess(first.getId()));
        assertEquals(
                xmin,
                jdbc.queryForObject(
                        "SELECT xmin::text FROM spring_session WHERE session_id=?",
                        String.class,
                        first.getId()));
        first.setMaxInactiveInterval(Duration.ofSeconds(1));
        first.setLastAccessedTime(Instant.now().minusSeconds(3));
        repository.save(first);
        assertNull(other.findById(first.getId()), "An expired session is never renewed on read");
    }

    @Test
    void pureTouchWithinWindowSkipsDatabaseRoundTripEntirely() {
        var session = create();
        String xmin =
                jdbc.queryForObject(
                        "SELECT xmin::text FROM spring_session WHERE session_id=?",
                        String.class,
                        session.getId());
        session.setLastAccessedTime(initial.plusMillis(500));
        repository.save(session);
        assertEquals(initial.toEpochMilli(), persistedAccess(session.getId()));
        assertEquals(
                xmin,
                jdbc.queryForObject(
                        "SELECT xmin::text FROM spring_session WHERE session_id=?",
                        String.class,
                        session.getId()));
    }

    @Test
    void touchBeyondWindowPerformsDatabaseRoundTrip() {
        var session = create();
        String xmin =
                jdbc.queryForObject(
                        "SELECT xmin::text FROM spring_session WHERE session_id=?",
                        String.class,
                        session.getId());
        session.setLastAccessedTime(initial.plusMillis(1000));
        repository.save(session);
        assertEquals(initial.plusMillis(1000).toEpochMilli(), persistedAccess(session.getId()));
        assertNotEquals(
                xmin,
                jdbc.queryForObject(
                        "SELECT xmin::text FROM spring_session WHERE session_id=?",
                        String.class,
                        session.getId()));
    }

    @Test
    void sessionExpiryBoundaryWithinWindowForcesPersistence() {
        var session = create();
        session.setMaxInactiveInterval(Duration.ofSeconds(1));
        session.setLastAccessedTime(Instant.now());
        repository.save(session);
        String xmin =
                jdbc.queryForObject(
                        "SELECT xmin::text FROM spring_session WHERE session_id=?",
                        String.class,
                        session.getId());
        Instant next = session.getLastAccessedTime().plusMillis(50);
        session.setLastAccessedTime(next);
        repository.save(session);
        assertEquals(next.toEpochMilli(), persistedAccess(session.getId()));
        assertNotEquals(
                xmin,
                jdbc.queryForObject(
                        "SELECT xmin::text FROM spring_session WHERE session_id=?",
                        String.class,
                        session.getId()));
    }

    @Test
    void explicitRevocationDeletesNonExpiringSessionsAndTheirAttributes() {
        var session = create();
        session.setMaxInactiveInterval(Duration.ofSeconds(-1));
        session.setAttribute("fixture", "retained until revocation");
        repository.save(session);
        assertNotNull(repository.findById(session.getId()));
        repository.deleteById(session.getId());
        assertNull(repository.findById(session.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM spring_session_attributes", Integer.class));
    }

    @Test
    void unchangedImmutableAttributeWithinWindowSkipsDatabaseWriteWhileChangedValuePersists() {
        var session = create();
        session.setAttribute("authRevision", "rev-1");
        repository.save(session);
        String sessionXmin =
                jdbc.queryForObject(
                        "SELECT xmin::text FROM spring_session WHERE session_id=?",
                        String.class,
                        session.getId());
        String attrXmin =
                jdbc.queryForObject(
                        "SELECT a.xmin::text FROM spring_session_attributes a"
                                + " JOIN spring_session s ON a.session_primary_id = s.primary_id"
                                + " WHERE s.session_id=? AND a.attribute_name=?",
                        String.class,
                        session.getId(),
                        "authRevision");

        var loaded = repository.findById(session.getId());
        assertNotNull(loaded);
        loaded.setLastAccessedTime(initial.plusMillis(200));
        loaded.setAttribute("authRevision", "rev-1");
        repository.save(loaded);

        assertEquals(
                sessionXmin,
                jdbc.queryForObject(
                        "SELECT xmin::text FROM spring_session WHERE session_id=?",
                        String.class,
                        session.getId()));
        assertEquals(
                attrXmin,
                jdbc.queryForObject(
                        "SELECT a.xmin::text FROM spring_session_attributes a"
                                + " JOIN spring_session s ON a.session_primary_id = s.primary_id"
                                + " WHERE s.session_id=? AND a.attribute_name=?",
                        String.class,
                        session.getId(),
                        "authRevision"));

        loaded.setAttribute("authRevision", "rev-2");
        repository.save(loaded);

        assertEquals("rev-2", repository.findById(session.getId()).getAttribute("authRevision"));
        assertNotEquals(
                attrXmin,
                jdbc.queryForObject(
                        "SELECT a.xmin::text FROM spring_session_attributes a"
                                + " JOIN spring_session s ON a.session_primary_id = s.primary_id"
                                + " WHERE s.session_id=? AND a.attribute_name=?",
                        String.class,
                        session.getId(),
                        "authRevision"));
    }
}
