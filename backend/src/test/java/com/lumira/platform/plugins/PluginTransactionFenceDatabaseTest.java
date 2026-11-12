package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.TestSchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class PluginTransactionFenceDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate sql;
    private DriverManagerDataSource raw;
    private DataSource fenced;
    private String schema;
    private final UUID node = UUID.randomUUID();
    private final PluginInvocationContext.Owner owner =
            new PluginInvocationContext.Owner("sample", "a".repeat(64), 1, node);

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL"),
                user = System.getenv("LUMIRA_TEST_DB_USER"),
                password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_plugin_fence_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        raw =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        fenced =
                "1".equals(System.getenv("LUMIRA_QA_UNFENCED_CONTROL"))
                        ? raw
                        : new PluginFencedDataSource(raw);
        sql = new JdbcTemplate(raw);
        for (String table :
                List.of(
                        "platform_plugin_artifact",
                        "platform_plugin_installation",
                        "platform_module_state",
                        "platform_plugin_runtime_node")) sql.execute(TestSchema.table(table));
        sql.update(
                "INSERT INTO platform_plugin_artifact(module_id,version,digest,manifest) VALUES('sample','1.0.0',?,'{}'::jsonb)",
                owner.digest());
        sql.update(
                "INSERT INTO platform_plugin_installation(module_id,digest,installed,explicit_install) VALUES('sample',?,true,true)",
                owner.digest());
        sql.update(
                "INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(?,'sample',true,1)",
                UUID.randomUUID());
        sql.update(
                "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) VALUES(?,true,false,'fixture')",
                node);
        sql.execute("CREATE TABLE business_records(id integer PRIMARY KEY)");
    }

    @AfterEach
    void cleanUp() {
        assertTrue(PluginInvocationContext.owners().isEmpty(), "Invocation ownership leaked");
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void readCommittedRequestsDoNotRewriteSharedPluginMetadata() throws Exception {
        var before = metadataTuples();
        for (int i = 0; i < 3; i++) {
            try (var connection = fenced.getConnection();
                    var scope = PluginInvocationContext.open(owner)) {
                execute(connection, "SELECT count(*) FROM business_records");
                execute(connection, "INSERT INTO business_records VALUES(" + i + ")");
            }
        }
        assertEquals(3, count());
        assertEquals(
                before,
                metadataTuples(),
                "Read-committed admission must not create metadata tuple writes");
    }

    private List<java.util.Map<String, Object>> metadataTuples() {
        return sql.queryForList(
                "SELECT 'installation' AS kind,xmin::text,xmax::text FROM platform_plugin_installation UNION ALL SELECT 'state',xmin::text,xmax::text FROM platform_module_state ORDER BY kind");
    }

    @Test
    void readCommittedRechecksAfterAnEarlierStatementSnapshot() throws Exception {
        try (var connection = fenced.getConnection()) {
            connection.setAutoCommit(false);
            execute(connection, "SELECT version FROM platform_module_state");
            sql.update("UPDATE platform_module_state SET version=2");
            try (var scope = PluginInvocationContext.open(owner)) {
                assertThrows(
                        SQLException.class,
                        () -> execute(connection, "INSERT INTO business_records VALUES(1)"));
            }
            assertThrows(SQLException.class, connection::commit);
        }
        assertEquals(0, count());
    }

    @Test
    void lostHeartbeatIsNotEvidenceOfDrainedBusinessTransactions() throws Exception {
        try (var connection = fenced.getConnection();
                var coordinator = raw.getConnection()) {
            connection.setAutoCommit(false);
            try (var scope = PluginInvocationContext.open(owner)) {
                execute(connection, "INSERT INTO business_records VALUES(1)");
            }
            expire();
            coordinator.setAutoCommit(false);
            var busy =
                    assertThrows(
                            SQLException.class,
                            () -> PluginTransactionFence.exclusive(coordinator, Set.of("sample")));
            assertEquals("40001", busy.getSQLState());
            coordinator.rollback();
            connection
                    .close(); // Simulate a lost database connection, not just a missing heartbeat.
            PluginTransactionFence.exclusive(coordinator, Set.of("sample"));
            execute(coordinator, "UPDATE platform_plugin_installation SET installed=false");
            execute(coordinator, "UPDATE platform_module_state SET enabled=false,version=2");
            coordinator.commit();
            assertEquals(0, count());
        }
        try (var connection = fenced.getConnection();
                var scope = PluginInvocationContext.open(owner)) {
            var stopped =
                    assertThrows(
                            SQLException.class,
                            () -> execute(connection, "INSERT INTO business_records VALUES(2)"));
            assertEquals("40001", stopped.getSQLState());
        }
        assertEquals(0, count());
    }

    @Test
    void autoCommitStatementsHoldTheirFenceThroughTheActualDatabaseCommit() throws Exception {
        try (var blocker = raw.getConnection();
                var coordinator = raw.getConnection();
                var workers = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            execute(blocker, "SELECT pg_advisory_lock(98712345)");
            sql.execute(
                    """
                    CREATE FUNCTION hold_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN PERFORM pg_advisory_xact_lock(98712345); RETURN NEW; END $$;
                    CREATE TRIGGER hold_insert BEFORE INSERT ON business_records FOR EACH ROW EXECUTE FUNCTION hold_insert();
                    """);
            var started = new java.util.concurrent.CountDownLatch(1);
            var result =
                    workers.submit(
                            () -> {
                                try (var connection = fenced.getConnection();
                                        var scope = PluginInvocationContext.open(owner)) {
                                    started.countDown();
                                    execute(connection, "INSERT INTO business_records VALUES(1)");
                                    assertTrue(connection.getAutoCommit());
                                }
                                return true;
                            });
            try {
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
                while (sql.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='advisory' AND query LIKE 'INSERT INTO business_records%'",
                                Integer.class)
                        == 0) {
                    assertTrue(
                            System.nanoTime() < deadline,
                            "Insert never reached the held database trigger");
                    Thread.sleep(10);
                }
                coordinator.setAutoCommit(false);
                assertThrows(
                        SQLException.class,
                        () -> PluginTransactionFence.exclusive(coordinator, Set.of("sample")));
                coordinator.rollback();
                assertFalse(result.isDone());
            } finally {
                execute(blocker, "SELECT pg_advisory_unlock(98712345)");
            }
            assertTrue(result.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, count());
            PluginTransactionFence.exclusive(coordinator, Set.of("sample"));
            coordinator.rollback();
        }
    }

    @Test
    void readOnlyWorkParticipatesWithoutUsingForbiddenRowLocks() throws Exception {
        try (var connection = fenced.getConnection();
                var coordinator = raw.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (var scope = PluginInvocationContext.open(owner)) {
                execute(connection, "SELECT count(*) FROM business_records");
            }
            expire();
            coordinator.setAutoCommit(false);
            assertThrows(
                    SQLException.class,
                    () -> PluginTransactionFence.exclusive(coordinator, Set.of("sample")));
            coordinator.rollback();
            connection.commit();
            PluginTransactionFence.exclusive(coordinator, Set.of("sample"));
            coordinator.rollback();
        }
    }

    @Test
    void catchingAFenceFailureCannotCommitEarlierOrSubsequentWrites() throws Exception {
        try (var connection = fenced.getConnection()) {
            connection.setAutoCommit(false);
            execute(connection, "INSERT INTO business_records VALUES(1)");
            expire();
            try (var scope = PluginInvocationContext.open(owner)) {
                assertThrows(
                        SQLException.class,
                        () -> execute(connection, "INSERT INTO business_records VALUES(2)"));
            }
            assertThrows(
                    SQLException.class,
                    () -> execute(connection, "INSERT INTO business_records VALUES(3)"));
            assertThrows(SQLException.class, connection::commit);
        }
        assertEquals(0, count());
    }

    @Test
    void savepointRollbackRequiresAdmissionAgain() throws Exception {
        try (var connection = fenced.getConnection()) {
            connection.setAutoCommit(false);
            var savepoint = connection.setSavepoint();
            try (var scope = PluginInvocationContext.open(owner)) {
                execute(connection, "INSERT INTO business_records VALUES(1)");
            }
            connection.rollback(savepoint);
            expire();
            try (var scope = PluginInvocationContext.open(owner)) {
                assertThrows(
                        SQLException.class,
                        () -> execute(connection, "INSERT INTO business_records VALUES(2)"));
            }
            assertThrows(SQLException.class, connection::commit);
        }
        assertEquals(0, count());
    }

    @Test
    void writableRepeatableReadDoesNotAcceptAnOldInstallationSnapshot() throws Exception {
        try (var connection = fenced.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            execute(connection, "SELECT version FROM platform_module_state");
            sql.update("UPDATE platform_module_state SET version=2");
            try (var scope = PluginInvocationContext.open(owner)) {
                var failure =
                        assertThrows(
                                SQLException.class,
                                () ->
                                        execute(
                                                connection,
                                                "INSERT INTO business_records VALUES(1)"));
                assertEquals("40001", failure.getSQLState());
            }
            assertThrows(SQLException.class, connection::commit);
        }
        assertEquals(0, count());
    }

    @Test
    void ignoredReadOnlyHintDoesNotAllowAWritableStaleSnapshot() throws Exception {
        var ignored =
                new DriverManagerDataSource(
                        raw.getUrl() + "&readOnlyMode=ignore",
                        raw.getUsername(),
                        raw.getPassword());
        try (var connection = new PluginFencedDataSource(ignored).getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            execute(connection, "SELECT version FROM platform_module_state");
            sql.update("UPDATE platform_module_state SET version=2");
            try (var scope = PluginInvocationContext.open(owner)) {
                var failure =
                        assertThrows(
                                SQLException.class,
                                () ->
                                        execute(
                                                connection,
                                                "INSERT INTO business_records VALUES(1)"));
                assertEquals("40001", failure.getSQLState());
            }
            assertThrows(SQLException.class, connection::commit);
        }
        assertEquals(0, count());
    }

    @Test
    void nativePostgresAccessStillAcquiresTheOwningTransactionFence() throws Exception {
        try (var connection = fenced.getConnection();
                var coordinator = raw.getConnection()) {
            connection.setAutoCommit(false);
            try (var scope = PluginInvocationContext.open(owner)) {
                assertNotNull(connection.unwrap(org.postgresql.PGConnection.class));
            }
            coordinator.setAutoCommit(false);
            assertThrows(
                    SQLException.class,
                    () -> PluginTransactionFence.exclusive(coordinator, Set.of("sample")));
            coordinator.rollback();
            connection.rollback();
            PluginTransactionFence.exclusive(coordinator, Set.of("sample"));
            coordinator.rollback();
        }
    }

    @Test
    void autoCommitResultsKeepTheirRowsAndGeneratedKeysAfterTheShortTransaction() throws Exception {
        try (var connection = fenced.getConnection();
                var scope = PluginInvocationContext.open(owner);
                var statement = connection.createStatement()) {
            statement.setFetchSize(2);
            try (var result = statement.executeQuery("SELECT generate_series(1,10)")) {
                int rows = 0;
                while (result.next()) {
                    rows++;
                    assertEquals(rows, result.getInt(1));
                }
                assertEquals(10, rows);
            }
            assertEquals(2, statement.getFetchSize());
            try (var result =
                    statement.executeQuery(
                            "INSERT INTO business_records VALUES(11) RETURNING id")) {
                assertTrue(result.next());
                assertEquals(11, result.getInt(1));
            }
            assertTrue(connection.getAutoCommit());
        }
        assertEquals(1, count());
    }

    @Test
    void sqlControlCannotCommitAndContinueInsideOneFencedStatement() throws Exception {
        try (var connection = fenced.getConnection();
                var scope = PluginInvocationContext.open(owner)) {
            assertThrows(
                    SQLException.class,
                    () ->
                            execute(
                                    connection,
                                    "INSERT INTO business_records VALUES(1); COMMIT; INSERT INTO business_records VALUES(2)"));
            assertTrue(connection.getAutoCommit());
            try (var prepared = connection.prepareStatement("SELECT ';commit;'::text")) {
                assertTrue(prepared.execute());
            }
            try (var batch = connection.createStatement()) {
                batch.addBatch("/* outer /* nested */ comment */ COMMIT");
                assertThrows(SQLException.class, batch::executeBatch);
            }
            execute(
                    connection,
                    "INSERT INTO business_records VALUES(3); INSERT INTO business_records VALUES(4)");
        }
        assertEquals(2, count());
    }

    private void expire() {
        sql.update(
                "UPDATE platform_plugin_runtime_node SET heartbeat_at=clock_timestamp()-interval '31 seconds'");
    }

    private int count() {
        return sql.queryForObject("SELECT count(*) FROM business_records", Integer.class);
    }

    private static void execute(Connection connection, String statement) throws SQLException {
        try (var query = connection.createStatement()) {
            query.execute(statement);
        }
    }
}
