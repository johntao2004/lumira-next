package com.lumira.platform.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class ClusterOperationJournalDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ClusterOperationJournal journal;
    private String schema;
    private final UUID id = UUID.randomUUID();
    private final UUID node = UUID.randomUUID();
    private final UUID peer = UUID.randomUUID();
    private final UUID token = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final String base = "a".repeat(64);
    private final String target = "b".repeat(64);

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_cluster_journal_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        journal = new ClusterOperationJournal(dataSource, new JsonMapper());
        jdbc.execute(com.lumira.platform.TestSchema.table("platform_plugin_runtime_node"));
        var migrations = Path.of("../plugins/cluster-runtime/migrations");
        var script =
                migrations.resolve(
                        PluginInitializationMigrationRunner.readCatalog(
                                        "cluster-runtime", migrations)
                                .script());
        // Execute the actual independent plugin initialization, including PL/pgSQL function bodies.
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(Files.readString(script));
        }
        jdbc.execute("CREATE TABLE graph_publication(revision text NOT NULL)");
        tx.executeWithoutResult(
                status -> {
                    jdbc.queryForObject(
                            "SELECT set_config('lumira.cluster_protocol','2',true)", String.class);
                    for (var participant : Set.of(node, peer))
                        jdbc.update(
                                "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) VALUES(?,true,false,?)",
                                participant,
                                base);
                });
    }

    @AfterEach
    void cleanup() {
        if (schema != null && admin != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private ClusterOperationJournal.Operation begin() {
        return tx.execute(
                status ->
                        journal.begin(
                                id,
                                node,
                                token,
                                base,
                                new ClusterOperationJournal.Decision(
                                        actor,
                                        ClusterOperationJournal.Kind.INSTALL,
                                        "sample",
                                        target,
                                        Map.of("sample", "c".repeat(64)),
                                        List.of("sample"),
                                        List.of(),
                                        List.of()),
                                Set.of(node, peer)));
    }

    @Test
    void forwardMigrationPreservesTheRealV1HistoryAndJournalRows(
            @org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        var current = Path.of("../plugins/cluster-runtime/migrations");
        var catalog = PluginInitializationMigrationRunner.readCatalog("cluster-runtime", current);
        String full = Files.readString(current.resolve(catalog.script()));
        String old =
                full.substring(0, full.indexOf("CREATE TABLE plugin_cluster_runtime_activation"));
        old =
                old.replace(
                                "    phase_started_at timestamptz NOT NULL DEFAULT clock_timestamp(),\n",
                                "")
                        .replace("    failure_code text,\n", "")
                        .replace(
                                "SET phase=next_phase,phase_started_at=clock_timestamp(),updated_at=clock_timestamp()",
                                "SET phase=next_phase,updated_at=clock_timestamp()");
        old =
                old.replace(
                        "    -- A failed participant cannot ACK a successful stage. Owner-fenced abort is a decision,\n"
                                + "    -- not a successful-stage quorum. Cleanup ACKs are still required before ABORTED.\n"
                                + "    IF next_phase<>'ABORTING' AND EXISTS(",
                        "    IF EXISTS(");
        Files.writeString(temporary.resolve("V1__plugin_initialization.sql"), old);
        Files.writeString(
                temporary.resolve("initialization.json"),
                new JsonMapper()
                        .writeValueAsString(
                                Map.of(
                                        "formatVersion",
                                        1,
                                        "pluginId",
                                        "cluster-runtime",
                                        "version",
                                        "1",
                                        "script",
                                        "V1__plugin_initialization.sql",
                                        "publishedMigrations",
                                        catalog.publishedMigrations())));
        String upgradeSchema =
                "qa_cluster_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + upgradeSchema);
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        var upgradeSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + upgradeSchema,
                        System.getenv("LUMIRA_TEST_DB_USER"),
                        System.getenv("LUMIRA_TEST_DB_PASSWORD"));
        var upgrade = new JdbcTemplate(upgradeSource);
        try {
            upgrade.execute(com.lumira.platform.TestSchema.table("platform_plugin_runtime_node"));
            PluginInitializationMigrationRunner.migrate(
                    upgradeSource, "cluster-runtime", temporary);
            assertThat(
                            upgrade.queryForObject(
                                    "SELECT checksum FROM plugin_cluster_runtime_schema_history WHERE version='1'",
                                    Integer.class))
                    .isEqualTo(-62550978);
            var before =
                    upgrade.queryForList(
                            "SELECT * FROM plugin_cluster_runtime_schema_history ORDER BY installed_rank");
            upgrade.update(
                    """
                    INSERT INTO plugin_cluster_runtime_operation(id,owner_node_id,owner_token,lease_expires_at,phase,base_revision,target)
                    VALUES(?,?,?,clock_timestamp(),'ABORTED',?,CAST(? AS jsonb))
                    """,
                    id,
                    node,
                    token,
                    base,
                    "{\"retain\":true}");
            PluginInitializationMigrationRunner.migrate(upgradeSource, "cluster-runtime", current);
            var after =
                    upgrade.queryForList(
                            "SELECT * FROM plugin_cluster_runtime_schema_history ORDER BY installed_rank");
            assertThat(after).hasSize(before.size() + catalog.evolutions().size());
            assertThat(after.subList(0, before.size())).isEqualTo(before);
            assertThat(
                            upgrade.queryForObject(
                                    "SELECT target->>'retain' FROM plugin_cluster_runtime_operation WHERE id=?",
                                    String.class,
                                    id))
                    .isEqualTo("true");
            assertThat(
                            upgrade.queryForObject(
                                    "SELECT active FROM plugin_cluster_runtime_activation",
                                    Boolean.class))
                    .isFalse();
            PluginInitializationMigrationRunner.migrate(upgradeSource, "cluster-runtime", current);
            assertThat(
                            upgrade.queryForList(
                                    "SELECT * FROM plugin_cluster_runtime_schema_history ORDER BY installed_rank"))
                    .isEqualTo(after);
        } finally {
            admin.execute("DROP SCHEMA " + upgradeSchema + " CASCADE");
        }
    }

    @Test
    void freshV3HistoryIsPreservedByRealV4ForwardMigration(
            @org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        var current = Path.of("../plugins/cluster-runtime/migrations");
        var catalog = PluginInitializationMigrationRunner.readCatalog("cluster-runtime", current);
        String full = Files.readString(current.resolve(catalog.script()));
        String v3 =
                full.substring(0, full.indexOf("ALTER TABLE plugin_cluster_runtime_participant\n"));
        Files.writeString(temporary.resolve("V3__plugin_initialization.sql"), v3);
        Files.writeString(
                temporary.resolve("initialization.json"),
                new JsonMapper()
                        .writeValueAsString(
                                Map.of(
                                        "formatVersion",
                                        2,
                                        "pluginId",
                                        "cluster-runtime",
                                        "version",
                                        "3",
                                        "script",
                                        "V3__plugin_initialization.sql",
                                        "publishedMigrations",
                                        catalog.publishedMigrations(),
                                        "previousInitializations",
                                        catalog.previousInitializations().subList(0, 2),
                                        "evolutions",
                                        catalog.evolutions().subList(0, 2))));
        String upgradeSchema =
                "qa_cluster_v3_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + upgradeSchema);
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + upgradeSchema,
                        System.getenv("LUMIRA_TEST_DB_USER"),
                        System.getenv("LUMIRA_TEST_DB_PASSWORD"));
        var upgrade = new JdbcTemplate(source);
        try {
            upgrade.execute(com.lumira.platform.TestSchema.table("platform_plugin_runtime_node"));
            PluginInitializationMigrationRunner.migrate(source, "cluster-runtime", temporary);
            var before =
                    upgrade.queryForList(
                            "SELECT * FROM plugin_cluster_runtime_schema_history ORDER BY installed_rank");
            assertThat(
                            upgrade.queryForObject(
                                    "SELECT checksum FROM plugin_cluster_runtime_schema_history WHERE version='3'",
                                    Integer.class))
                    .isEqualTo(719685693);
            PluginInitializationMigrationRunner.migrate(source, "cluster-runtime", current);
            var after =
                    upgrade.queryForList(
                            "SELECT * FROM plugin_cluster_runtime_schema_history ORDER BY installed_rank");
            assertThat(after).hasSize(before.size() + 1);
            assertThat(after.subList(0, before.size())).isEqualTo(before);
            PluginInitializationMigrationRunner.migrate(source, "cluster-runtime", current);
            assertThat(
                            upgrade.queryForList(
                                    "SELECT * FROM plugin_cluster_runtime_schema_history ORDER BY installed_rank"))
                    .isEqualTo(after);
        } finally {
            admin.execute("DROP SCHEMA " + upgradeSchema + " CASCADE");
        }
    }

    @Test
    void freshV2HistoryIsPreservedByRealV4ForwardMigration(
            @org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        var current = Path.of("../plugins/cluster-runtime/migrations");
        var catalog = PluginInitializationMigrationRunner.readCatalog("cluster-runtime", current);
        String full = Files.readString(current.resolve(catalog.script()));
        String v2 =
                full.substring(0, full.indexOf("CREATE FUNCTION plugin_cluster_runtime_veto"))
                        .replace(
                                "    phase_started_at timestamptz NOT NULL DEFAULT clock_timestamp(),\n",
                                "")
                        .replace("    failure_code text,\n", "")
                        .replace(
                                "SET phase=next_phase,phase_started_at=clock_timestamp(),updated_at=clock_timestamp()",
                                "SET phase=next_phase,updated_at=clock_timestamp()")
                        .replace("    admitted boolean NOT NULL DEFAULT false,\n", "")
                        .replace(
                                "    IF (NEW.ready OR EXISTS(SELECT 1 FROM plugin_cluster_runtime_node_capability\n"
                                        + "      WHERE node_id=NEW.node_id AND admitted)) AND EXISTS(SELECT 1 FROM plugin_cluster_runtime_operation o\n"
                                        + "      WHERE o.phase NOT IN ('COMPLETED','ABORTED') AND NOT EXISTS(\n"
                                        + "        SELECT 1 FROM plugin_cluster_runtime_participant p\n"
                                        + "        WHERE p.operation_id=o.id AND p.node_id=NEW.node_id)) THEN\n"
                                        + "        RAISE EXCEPTION 'pluginClusterJoinPending' USING ERRCODE='55000';\n"
                                        + "    END IF;\n"
                                        + "    INSERT INTO plugin_cluster_runtime_node_capability(node_id,protocol_version,observed_heartbeat,admitted)\n"
                                        + "      VALUES(NEW.node_id,declared,NEW.heartbeat_at,NEW.ready)\n"
                                        + "      ON CONFLICT(node_id) DO UPDATE SET protocol_version=EXCLUDED.protocol_version,\n"
                                        + "        observed_heartbeat=EXCLUDED.observed_heartbeat,observed_at=clock_timestamp(),admitted=plugin_cluster_runtime_node_capability.admitted OR EXCLUDED.admitted;",
                                "    INSERT INTO plugin_cluster_runtime_node_capability(node_id,protocol_version,observed_heartbeat)\n"
                                        + "      VALUES(NEW.node_id,declared,NEW.heartbeat_at)\n"
                                        + "      ON CONFLICT(node_id) DO UPDATE SET protocol_version=EXCLUDED.protocol_version,\n"
                                        + "        observed_heartbeat=EXCLUDED.observed_heartbeat,observed_at=clock_timestamp();");
        Files.writeString(temporary.resolve("V2__plugin_initialization.sql"), v2);
        Files.writeString(
                temporary.resolve("initialization.json"),
                new JsonMapper()
                        .writeValueAsString(
                                Map.of(
                                        "formatVersion",
                                        2,
                                        "pluginId",
                                        "cluster-runtime",
                                        "version",
                                        "2",
                                        "script",
                                        "V2__plugin_initialization.sql",
                                        "publishedMigrations",
                                        catalog.publishedMigrations(),
                                        "previousInitializations",
                                        catalog.previousInitializations().subList(0, 1),
                                        "evolutions",
                                        catalog.evolutions().subList(0, 1))));
        String upgradeSchema =
                "qa_cluster_v2_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + upgradeSchema);
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + upgradeSchema,
                        System.getenv("LUMIRA_TEST_DB_USER"),
                        System.getenv("LUMIRA_TEST_DB_PASSWORD"));
        var upgrade = new JdbcTemplate(source);
        try {
            upgrade.execute(com.lumira.platform.TestSchema.table("platform_plugin_runtime_node"));
            PluginInitializationMigrationRunner.migrate(source, "cluster-runtime", temporary);
            var before =
                    upgrade.queryForList(
                            "SELECT * FROM plugin_cluster_runtime_schema_history ORDER BY installed_rank");
            assertThat(
                            upgrade.queryForObject(
                                    "SELECT checksum FROM plugin_cluster_runtime_schema_history WHERE version='2'",
                                    Integer.class))
                    .isEqualTo(267369361);
            PluginInitializationMigrationRunner.migrate(source, "cluster-runtime", current);
            var after =
                    upgrade.queryForList(
                            "SELECT * FROM plugin_cluster_runtime_schema_history ORDER BY installed_rank");
            assertThat(after).hasSize(before.size() + 2);
            assertThat(after.subList(0, before.size())).isEqualTo(before);
            PluginInitializationMigrationRunner.migrate(source, "cluster-runtime", current);
            assertThat(
                            upgrade.queryForList(
                                    "SELECT * FROM plugin_cluster_runtime_schema_history ORDER BY installed_rank"))
                    .isEqualTo(after);
        } finally {
            admin.execute("DROP SCHEMA " + upgradeSchema + " CASCADE");
        }
    }

    @Test
    void participantVetoPersistsItsFailureAndDoesNotAuthorizeCommit() {
        var owner = begin().owner();
        tx.executeWithoutResult(
                status -> {
                    assertThat(journal.veto(id, UUID.randomUUID(), 1, "pluginStartFailed"))
                            .isFalse();
                    assertThat(journal.veto(id, peer, 1, "pluginStartFailed")).isTrue();
                    var operation = journal.find(id).orElseThrow();
                    assertThat(operation.phase()).isEqualTo(ClusterOperationJournal.Phase.ABORTING);
                    assertThat(operation.failureCode()).isEqualTo("pluginStartFailed");
                    assertThat(
                                    journal.advance(
                                            id,
                                            owner,
                                            ClusterOperationJournal.Phase.STAGING,
                                            ClusterOperationJournal.Phase.COMMITTED))
                            .isFalse();
                });
    }

    @Test
    void aPreviouslyReadyExpiredNodeCannotRenewOutsideTheFrozenParticipantSet() {
        UUID returning = UUID.randomUUID(), joining = UUID.randomUUID();
        tx.executeWithoutResult(
                status -> {
                    jdbc.queryForObject(
                            "SELECT set_config('lumira.cluster_protocol','2',true)", String.class);
                    jdbc.update(
                            "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) VALUES(?,true,false,?)",
                            returning,
                            base);
                    jdbc.update(
                            "UPDATE platform_plugin_runtime_node SET ready=false,heartbeat_at=clock_timestamp()-interval '31 seconds' WHERE node_id=?",
                            returning);
                });
        begin();
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            jdbc.queryForObject(
                                                    "SELECT set_config('lumira.cluster_protocol','2',true)",
                                                    String.class);
                                            jdbc.update(
                                                    "UPDATE platform_plugin_runtime_node SET heartbeat_at=clock_timestamp(),ready=false WHERE node_id=?",
                                                    returning);
                                        }))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        tx.executeWithoutResult(
                status -> {
                    jdbc.queryForObject(
                            "SELECT set_config('lumira.cluster_protocol','2',true)", String.class);
                    jdbc.update(
                            "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) VALUES(?,false,false,?)",
                            joining,
                            base);
                    assertThat(
                                    jdbc.update(
                                            "UPDATE platform_plugin_runtime_node SET heartbeat_at=clock_timestamp() WHERE node_id=?",
                                            joining))
                            .isEqualTo(1);
                });
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            jdbc.queryForObject(
                                                    "SELECT set_config('lumira.cluster_protocol','2',true)",
                                                    String.class);
                                            jdbc.update(
                                                    "UPDATE platform_plugin_runtime_node SET ready=true WHERE node_id=?",
                                                    joining);
                                        }))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void rejectsAReadyNodeWithADifferentLoadedGraph() {
        tx.executeWithoutResult(
                status -> {
                    jdbc.queryForObject(
                            "SELECT set_config('lumira.cluster_protocol','2',true)", String.class);
                    jdbc.update(
                            "UPDATE platform_plugin_runtime_node SET graph_revision=? WHERE node_id=?",
                            target,
                            peer);
                });
        assertThatThrownBy(() -> tx.execute(status -> journal.activate(base)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT active FROM plugin_cluster_runtime_activation",
                                Boolean.class))
                .isFalse();
    }

    @Test
    void activationSerializesWithARealConcurrentLegacyRegistration() throws Exception {
        UUID legacy = UUID.randomUUID();
        String app = "qa_cluster_join_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        var joining =
                new DriverManagerDataSource(
                        url
                                + (url.contains("?") ? "&" : "?")
                                + "currentSchema="
                                + schema
                                + "&ApplicationName="
                                + app,
                        System.getenv("LUMIRA_TEST_DB_USER"),
                        System.getenv("LUMIRA_TEST_DB_PASSWORD"));
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result =
                    tx.execute(
                            status -> {
                                assertThat(journal.activate(base))
                                        .containsExactlyInAnyOrder(node, peer);
                                var future =
                                        executor.submit(
                                                () -> {
                                                    try (var connection = joining.getConnection();
                                                            var statement =
                                                                    connection.prepareStatement(
                                                                            "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) VALUES(?,true,false,?)")) {
                                                        statement.setObject(1, legacy);
                                                        statement.setString(2, base);
                                                        statement.setQueryTimeout(10);
                                                        statement.executeUpdate();
                                                        return "INSERTED";
                                                    } catch (java.sql.SQLException failure) {
                                                        return failure.getSQLState();
                                                    }
                                                });
                                long deadline =
                                        System.nanoTime()
                                                + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                                boolean blocked = false;
                                while (System.nanoTime() < deadline) {
                                    if (admin.queryForObject(
                                                    "SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",
                                                    Integer.class,
                                                    app)
                                            == 1) {
                                        blocked = true;
                                        break;
                                    }
                                    try {
                                        Thread.sleep(20);
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(interrupted);
                                    }
                                }
                                assertThat(blocked)
                                        .as(
                                                "legacy registration waits on the activation membership lock")
                                        .isTrue();
                                assertThat(future.isDone()).isFalse();
                                return future;
                            });
            assertThat(result.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("55000");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM platform_plugin_runtime_node WHERE node_id=?",
                                Integer.class,
                                legacy))
                .isZero();
    }

    @Test
    void rejectsOldNodesOnActivationAndRejectsUntaggedWritersUntilExplicitDeactivation() {
        UUID legacy = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) VALUES(?,true,false,?)",
                legacy,
                base);
        assertThatThrownBy(() -> tx.execute(status -> journal.activate(base)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT active FROM plugin_cluster_runtime_activation",
                                Boolean.class))
                .isFalse();
        jdbc.update("DELETE FROM platform_plugin_runtime_node WHERE node_id=?", legacy);
        tx.executeWithoutResult(
                status -> assertThat(journal.activate(base)).containsExactlyInAnyOrder(node, peer));
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE platform_plugin_runtime_node SET heartbeat_at=clock_timestamp() WHERE node_id=?",
                                        node))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) VALUES(?,true,false,?)",
                                        legacy,
                                        base))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        tx.executeWithoutResult(status -> assertThat(journal.deactivate()).isTrue());
        assertThat(
                        jdbc.update(
                                "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) VALUES(?,true,false,?)",
                                legacy,
                                base))
                .isEqualTo(1);
    }

    @Test
    void ownerCanAbortFailedPreparationWithoutFalseSuccessAcksButCleanupStillRequiresEveryNode() {
        var owner = begin().owner();
        tx.executeWithoutResult(
                status -> {
                    assertThat(journal.deactivate()).isFalse();
                    assertThat(
                                    journal.advance(
                                            id,
                                            owner,
                                            ClusterOperationJournal.Phase.PREPARING,
                                            ClusterOperationJournal.Phase.ABORTING))
                            .isTrue();
                    assertThat(
                                    journal.advance(
                                            id,
                                            owner,
                                            ClusterOperationJournal.Phase.ABORTING,
                                            ClusterOperationJournal.Phase.ABORTED))
                            .isFalse();
                    for (var participant : Set.of(node, peer))
                        assertThat(
                                        journal.acknowledge(
                                                id,
                                                participant,
                                                1,
                                                ClusterOperationJournal.Phase.ABORTING,
                                                base))
                                .isTrue();
                    assertThat(
                                    journal.advance(
                                            id,
                                            owner,
                                            ClusterOperationJournal.Phase.ABORTING,
                                            ClusterOperationJournal.Phase.ABORTED))
                            .isTrue();
                    assertThat(journal.deactivate()).isTrue();
                });
    }

    @Test
    void rejectsTransactionBoundToAnotherDataSourceAndUnsupportedIsolation() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        var other =
                new DriverManagerDataSource(
                        url,
                        System.getenv("LUMIRA_TEST_DB_USER"),
                        System.getenv("LUMIRA_TEST_DB_PASSWORD"));
        var foreign = new TransactionTemplate(new DataSourceTransactionManager(other));
        foreign.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        assertThatThrownBy(() -> foreign.execute(status -> journal.active()))
                .isInstanceOf(IllegalStateException.class);
        tx.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        assertThatThrownBy(() -> tx.execute(status -> journal.active()))
                .isInstanceOf(IllegalStateException.class);
        tx.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        tx.setReadOnly(true);
        assertThatThrownBy(() -> tx.execute(status -> journal.active()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM plugin_cluster_runtime_operation",
                                Integer.class))
                .isZero();
    }

    @Test
    void preservesActorTargetAndFrozenParticipantsAndRejectsAutocommit() {
        assertThatThrownBy(() -> journal.active()).isInstanceOf(IllegalStateException.class);
        var operation = begin();
        assertThat(journal.blocksGraphChange()).isTrue();
        tx.executeWithoutResult(
                status -> {
                    assertThat(journal.active()).contains(operation);
                    assertThat(journal.participants(id)).containsExactlyInAnyOrder(node, peer);
                    assertThat(operation.decision().actorId()).isEqualTo(actor);
                    assertThat(journal.claim(id, peer, UUID.randomUUID())).isEmpty();
                    assertThatThrownBy(
                                    () -> journal.claim(id, UUID.randomUUID(), UUID.randomUUID()))
                            .isInstanceOf(IllegalArgumentException.class);
                });
        assertThatThrownBy(this::begin)
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void requiresEveryParticipantAndCorrectRevisionAndFencesPriorOwnerAfterTakeover() {
        var old = begin().owner();
        tx.executeWithoutResult(
                status -> {
                    assertThat(
                                    journal.acknowledge(
                                            id,
                                            node,
                                            1,
                                            ClusterOperationJournal.Phase.PREPARING,
                                            target))
                            .isFalse();
                    assertThat(
                                    journal.acknowledge(
                                            id,
                                            node,
                                            1,
                                            ClusterOperationJournal.Phase.PREPARING,
                                            base))
                            .isTrue();
                    assertThat(
                                    journal.advance(
                                            id,
                                            old,
                                            ClusterOperationJournal.Phase.PREPARING,
                                            ClusterOperationJournal.Phase.DRAINING))
                            .isFalse();
                });
        jdbc.update(
                "UPDATE plugin_cluster_runtime_operation SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
                id);
        var replacement =
                tx.execute(status -> journal.claim(id, peer, UUID.randomUUID()).orElseThrow());
        assertThat(replacement.epoch()).isEqualTo(2);
        tx.executeWithoutResult(
                status -> {
                    assertThat(journal.find(id).orElseThrow().decision().actorId())
                            .isEqualTo(actor);
                    assertThat(journal.renew(id, old)).isFalse();
                    assertThat(
                                    journal.acknowledge(
                                            id,
                                            node,
                                            1,
                                            ClusterOperationJournal.Phase.PREPARING,
                                            base))
                            .isFalse();
                    assertThat(
                                    journal.advance(
                                            id,
                                            replacement,
                                            ClusterOperationJournal.Phase.PREPARING,
                                            ClusterOperationJournal.Phase.DRAINING))
                            .isFalse();
                    for (var participant : Set.of(node, peer))
                        assertThat(
                                        journal.acknowledge(
                                                id,
                                                participant,
                                                2,
                                                ClusterOperationJournal.Phase.PREPARING,
                                                base))
                                .isTrue();
                    assertThat(
                                    journal.advance(
                                            id,
                                            old,
                                            ClusterOperationJournal.Phase.PREPARING,
                                            ClusterOperationJournal.Phase.DRAINING))
                            .isFalse();
                    assertThat(
                                    journal.advance(
                                            id,
                                            replacement,
                                            ClusterOperationJournal.Phase.PREPARING,
                                            ClusterOperationJournal.Phase.DRAINING))
                            .isTrue();
                });
    }

    @Test
    void decisionAndGraphPublicationRollbackTogetherThenRetryWithoutNewOperation() {
        var owner = begin().owner();
        for (var phase :
                List.of(
                        ClusterOperationJournal.Phase.PREPARING,
                        ClusterOperationJournal.Phase.DRAINING)) {
            tx.executeWithoutResult(
                    status -> {
                        for (var participant : Set.of(node, peer))
                            journal.acknowledge(id, participant, 1, phase, base);
                        var next =
                                phase == ClusterOperationJournal.Phase.PREPARING
                                        ? ClusterOperationJournal.Phase.DRAINING
                                        : ClusterOperationJournal.Phase.STAGING;
                        assertThat(journal.advance(id, owner, phase, next)).isTrue();
                    });
        }
        tx.executeWithoutResult(
                status -> {
                    for (var participant : Set.of(node, peer))
                        journal.acknowledge(
                                id, participant, 1, ClusterOperationJournal.Phase.STAGING, base);
                });
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            assertThat(
                                                            journal.advance(
                                                                    id,
                                                                    owner,
                                                                    ClusterOperationJournal.Phase
                                                                            .STAGING,
                                                                    ClusterOperationJournal.Phase
                                                                            .COMMITTED))
                                                    .isTrue();
                                            jdbc.update(
                                                    "INSERT INTO graph_publication VALUES(?)",
                                                    target);
                                            throw new IllegalStateException(
                                                    "injected publication failure");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM graph_publication", Integer.class))
                .isZero();
        tx.executeWithoutResult(
                status -> {
                    assertThat(journal.find(id).orElseThrow().phase())
                            .isEqualTo(ClusterOperationJournal.Phase.STAGING);
                    jdbc.update("INSERT INTO graph_publication VALUES(?)", target);
                    assertThat(
                                    journal.advance(
                                            id,
                                            owner,
                                            ClusterOperationJournal.Phase.STAGING,
                                            ClusterOperationJournal.Phase.COMMITTED))
                            .isTrue();
                });
        tx.executeWithoutResult(
                status -> {
                    assertThat(
                                    journal.acknowledge(
                                            id,
                                            node,
                                            1,
                                            ClusterOperationJournal.Phase.COMMITTED,
                                            base))
                            .isFalse();
                    for (var participant : Set.of(node, peer))
                        assertThat(
                                        journal.acknowledge(
                                                id,
                                                participant,
                                                1,
                                                ClusterOperationJournal.Phase.COMMITTED,
                                                target))
                                .isTrue();
                    assertThat(
                                    journal.advance(
                                            id,
                                            owner,
                                            ClusterOperationJournal.Phase.COMMITTED,
                                            ClusterOperationJournal.Phase.COMPLETED))
                            .isTrue();
                    assertThat(journal.active()).isEmpty();
                    assertThat(journal.blocksGraphChange()).isFalse();
                    assertThat(journal.renew(id, owner)).isFalse();
                });
        assertThat(jdbc.queryForObject("SELECT revision FROM graph_publication", String.class))
                .isEqualTo(target);
    }

    @Test
    void refusesCascadingRemovalAndMissingOwnerBeforeCreatingAnyRows() {
        assertThatThrownBy(
                        () ->
                                new ClusterOperationJournal.Decision(
                                        actor,
                                        ClusterOperationJournal.Kind.UNINSTALL,
                                        "sample",
                                        target,
                                        Map.of(),
                                        List.of(),
                                        List.of(),
                                        List.of("sample", "dependent")))
                .isInstanceOf(IllegalArgumentException.class);
        var decision =
                new ClusterOperationJournal.Decision(
                        actor,
                        ClusterOperationJournal.Kind.UNINSTALL,
                        "sample",
                        target,
                        Map.of(),
                        List.of(),
                        List.of(),
                        List.of("sample"));
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status ->
                                                journal.begin(
                                                        id,
                                                        node,
                                                        token,
                                                        base,
                                                        decision,
                                                        Set.of(peer))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM plugin_cluster_runtime_operation",
                                Integer.class))
                .isZero();
    }
}
