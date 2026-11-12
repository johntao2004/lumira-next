package com.lumira.platform.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumira.platform.TestSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Recovery tests use real PostgreSQL connections and the actual final plugin initialization. */
@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class ClusterRecoveryDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;
    private TransactionTemplate tx;
    private ClusterOperationJournal journal;
    private String schema;
    private final UUID id = UUID.randomUUID();
    private final UUID node = UUID.randomUUID();
    private final UUID peer = UUID.randomUUID();
    private final UUID replacement = UUID.randomUUID();
    private final String base = "a".repeat(64);
    private final String target = "b".repeat(64);
    private final String digest = "c".repeat(64);

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_cluster_recovery_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        journal = new ClusterOperationJournal(source, new JsonMapper());
        for (String table :
                List.of(
                        "platform_plugin_artifact",
                        "platform_plugin_installation",
                        "platform_module_state",
                        "platform_plugin_runtime_node")) {
            jdbc.execute(TestSchema.table(table));
        }
        var migrations = Path.of("../plugins/cluster-runtime/migrations");
        var catalog =
                PluginInitializationMigrationRunner.readCatalog("cluster-runtime", migrations);
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(Files.readString(migrations.resolve(catalog.script())));
        }
        jdbc.update(
                "INSERT INTO platform_plugin_artifact(module_id,version,digest,manifest) "
                        + "VALUES('keeper','1.0.0',?,'{}'::jsonb)",
                digest);
        jdbc.update(
                "INSERT INTO platform_plugin_installation(module_id,digest,installed,explicit_install) "
                        + "VALUES('keeper',?,true,true)",
                digest);
        jdbc.update(
                "INSERT INTO platform_module_state(id,module_id,enabled,version) "
                        + "VALUES(?,'keeper',true,1)",
                UUID.randomUUID());
        jdbc.execute("CREATE TABLE recovery_business_records(id integer PRIMARY KEY)");
        jdbc.execute("CREATE TABLE recovery_graph_publication(revision text NOT NULL)");
        jdbc.update("INSERT INTO recovery_graph_publication VALUES(?)", base);
        register(node, true);
        register(peer, true);
    }

    @AfterEach
    void cleanup() {
        if (schema != null && admin != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void allNodeReplacementAbortsBeforeCommitAndPermanentlyRevokesRetiredNodes() {
        var original = begin().owner();
        expireOriginalNodesAndOwner();
        register(replacement, false);
        var recovered =
                tx.execute(
                        status ->
                                journal.recover(id, replacement, UUID.randomUUID()).orElseThrow());
        assertThat(recovered.epoch()).isEqualTo(2);
        tx.executeWithoutResult(
                status -> {
                    assertThat(journal.find(id).orElseThrow().phase())
                            .isEqualTo(ClusterOperationJournal.Phase.ABORTING);
                    assertThat(journal.retire(id, recovered, node)).isTrue();
                    assertThat(journal.retire(id, recovered, peer)).isTrue();
                    assertThat(journal.participants(id)).containsExactly(replacement);
                    assertThat(journal.renew(id, original)).isFalse();
                    assertThat(
                                    journal.acknowledge(
                                            id,
                                            peer,
                                            original.epoch(),
                                            ClusterOperationJournal.Phase.ABORTING,
                                            base))
                            .isFalse();
                    assertThat(
                                    journal.acknowledge(
                                            id,
                                            replacement,
                                            recovered.epoch(),
                                            ClusterOperationJournal.Phase.ABORTING,
                                            base))
                            .isTrue();
                    assertThat(
                                    journal.advance(
                                            id,
                                            recovered,
                                            ClusterOperationJournal.Phase.ABORTING,
                                            ClusterOperationJournal.Phase.ABORTED))
                            .isTrue();
                });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM plugin_cluster_runtime_participant WHERE operation_id=?",
                                Integer.class,
                                id))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM plugin_cluster_runtime_participant "
                                        + "WHERE operation_id=? AND retired_at IS NOT NULL AND retired_epoch=?",
                                Integer.class,
                                id,
                                recovered.epoch()))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision FROM recovery_graph_publication", String.class))
                .isEqualTo(base);
        assertRetiredNodeCannotReturn(node);
        assertRetiredNodeCannotReturn(peer);
        assertThat(activeOperation()).isEmpty();
        // An entirely new process identity remains admissible after recovery terminates.
        register(UUID.randomUUID(), true);
    }

    @Test
    void allNodeReplacementRollsForwardCommittedGraphWithoutRepeatingPublication() {
        var original = begin().owner();
        prepareToStage(original);
        tx.executeWithoutResult(
                status -> {
                    acknowledgeAll(ClusterOperationJournal.Phase.STAGING, original.epoch());
                    assertThat(
                                    journal.advance(
                                            id,
                                            original,
                                            ClusterOperationJournal.Phase.STAGING,
                                            ClusterOperationJournal.Phase.COMMITTED))
                            .isTrue();
                    jdbc.update("UPDATE recovery_graph_publication SET revision=?", target);
                });
        expireOriginalNodesAndOwner();
        register(replacement, false);
        var recovered =
                tx.execute(
                        status ->
                                journal.recover(id, replacement, UUID.randomUUID()).orElseThrow());
        tx.executeWithoutResult(
                status -> {
                    assertThat(journal.find(id).orElseThrow().phase())
                            .isEqualTo(ClusterOperationJournal.Phase.COMMITTED);
                    assertThat(journal.veto(id, replacement, recovered.epoch(), "mustNotRollback"))
                            .isFalse();
                    assertThat(journal.retire(id, recovered, node)).isTrue();
                    assertThat(journal.retire(id, recovered, peer)).isTrue();
                    assertThat(
                                    journal.acknowledge(
                                            id,
                                            replacement,
                                            recovered.epoch(),
                                            ClusterOperationJournal.Phase.COMMITTED,
                                            base))
                            .isFalse();
                    assertThat(
                                    journal.acknowledge(
                                            id,
                                            replacement,
                                            recovered.epoch(),
                                            ClusterOperationJournal.Phase.COMMITTED,
                                            target))
                            .isTrue();
                    assertThat(
                                    journal.advance(
                                            id,
                                            recovered,
                                            ClusterOperationJournal.Phase.COMMITTED,
                                            ClusterOperationJournal.Phase.COMPLETED))
                            .isTrue();
                    assertThat(
                                    journal.advance(
                                            id,
                                            original,
                                            ClusterOperationJournal.Phase.COMMITTED,
                                            ClusterOperationJournal.Phase.COMPLETED))
                            .isFalse();
                });
        assertThat(
                        jdbc.queryForList(
                                "SELECT revision FROM recovery_graph_publication", String.class))
                .containsExactly(target);
        assertRetiredNodeCannotReturn(peer);
    }

    @Test
    void freshOwnerAndUnregisteredStaleUnsupportedOrPreviouslyAdmittedRecoveryNodesAreRefused() {
        begin();
        register(replacement, false);
        assertThat(tryRecover(replacement)).isEmpty();
        expireOriginalNodesAndOwner();
        assertThat(tryRecover(UUID.randomUUID())).isEmpty();
        protocolWrite(
                () ->
                        jdbc.update(
                                "UPDATE platform_plugin_runtime_node "
                                        + "SET heartbeat_at=clock_timestamp()-interval '31 seconds' WHERE node_id=?",
                                replacement));
        assertThat(tryRecover(replacement)).isEmpty();
        protocolWrite(
                () ->
                        jdbc.update(
                                "UPDATE platform_plugin_runtime_node "
                                        + "SET heartbeat_at=clock_timestamp() WHERE node_id=?",
                                replacement));
        // Targeted corruption of the capability fixture verifies recovery's SQL-side checks.
        jdbc.update(
                "UPDATE plugin_cluster_runtime_node_capability SET protocol_version=0 WHERE node_id=?",
                replacement);
        assertThat(tryRecover(replacement)).isEmpty();
        jdbc.update(
                "UPDATE plugin_cluster_runtime_node_capability SET protocol_version=1,admitted=true WHERE node_id=?",
                replacement);
        assertThat(tryRecover(replacement)).isEmpty();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) "
                                                + "VALUES(?,false,false,'')",
                                        UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
        assertThat(participants()).containsExactlyInAnyOrder(node, peer);
    }

    @Test
    void retirementCannotBypassAnUnrelatedInstalledPluginsPhysicalTransactionFence()
            throws Exception {
        begin();
        try (var business = source.getConnection()) {
            business.setAutoCommit(false);
            // keeper is installed but absent from this operation's affected module set.
            PluginTransactionFence.enter(
                    business, new PluginInvocationContext.Owner("keeper", digest, 1, peer));
            execute(business, "INSERT INTO recovery_business_records VALUES(1)");
            int pid = backendPid(business);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_locks WHERE pid=? "
                                            + "AND locktype='advisory' AND mode='ShareLock' AND granted",
                                    Integer.class,
                                    pid))
                    .isPositive();
            expireOriginalNodesAndOwner();
            register(replacement, false);
            var recovered =
                    tx.execute(
                            status ->
                                    journal.recover(id, replacement, UUID.randomUUID())
                                            .orElseThrow());
            assertThat(retire(recovered, peer)).isFalse();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT retired_at IS NULL FROM plugin_cluster_runtime_participant "
                                            + "WHERE operation_id=? AND node_id=?",
                                    Boolean.class,
                                    id,
                                    peer))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM plugin_cluster_runtime_node_revocation WHERE node_id=?",
                                    Integer.class,
                                    peer))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM recovery_business_records",
                                    Integer.class))
                    .isZero();
            business.commit();
            assertThat(retire(recovered, peer)).isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM recovery_business_records",
                                    Integer.class))
                    .isEqualTo(1);
            assertThatThrownBy(
                            () ->
                                    PluginTransactionFence.enter(
                                            business,
                                            new PluginInvocationContext.Owner(
                                                    "keeper", digest, 1, peer)))
                    .isInstanceOf(java.sql.SQLException.class);
            business.rollback();
        }
    }

    @Test
    void pausedParticipantExecutionCannotBeRetiredUntilItsPhysicalConnectionEnds()
            throws Exception {
        begin();
        try (var callback = source.getConnection()) {
            callback.setAutoCommit(false);
            try (var statement =
                    callback.prepareStatement("SELECT plugin_cluster_runtime_enter(?,?,?,?)")) {
                statement.setObject(1, id);
                statement.setObject(2, peer);
                statement.setLong(3, 1);
                statement.setString(4, "PREPARING");
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                }
            }
            execute(callback, "INSERT INTO recovery_business_records VALUES(2)");
            expireOriginalNodesAndOwner();
            register(replacement, false);
            var recovered =
                    tx.execute(
                            status ->
                                    journal.recover(id, replacement, UUID.randomUUID())
                                            .orElseThrow());
            assertThat(retire(recovered, peer)).isFalse();
            assertThat(participants()).contains(peer);
            callback.close(); // A process's lost connection releases the physical execution lock.
            assertThat(retire(recovered, peer)).isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM recovery_business_records",
                                    Integer.class))
                    .isZero();
        }
    }

    @Test
    void takeoverBlockedBehindPhysicalCommitObservesCommittedDecisionAndFencesOldEpoch()
            throws Exception {
        var original = begin().owner();
        prepareToStage(original);
        tx.executeWithoutResult(
                status -> acknowledgeAll(ClusterOperationJournal.Phase.STAGING, original.epoch()));
        jdbc.update(
                "UPDATE plugin_cluster_runtime_operation SET lease_expires_at=clock_timestamp()+interval '2 seconds' WHERE id=?",
                id);
        var committedButNotPhysical = new CountDownLatch(1);
        var releaseCommit = new CountDownLatch(1);
        var ownerPid = new AtomicInteger();
        var claimantPid = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var owner =
                    workers.submit(
                            () ->
                                    tx.execute(
                                            status -> {
                                                ownerPid.set(
                                                        jdbc.queryForObject(
                                                                "SELECT pg_backend_pid()",
                                                                Integer.class));
                                                assertThat(
                                                                journal.advance(
                                                                        id,
                                                                        original,
                                                                        ClusterOperationJournal
                                                                                .Phase.STAGING,
                                                                        ClusterOperationJournal
                                                                                .Phase.COMMITTED))
                                                        .isTrue();
                                                jdbc.update(
                                                        "UPDATE recovery_graph_publication SET revision=?",
                                                        target);
                                                committedButNotPhysical.countDown();
                                                await(releaseCommit);
                                                return true;
                                            }));
            try {
                assertThat(committedButNotPhysical.await(5, TimeUnit.SECONDS)).isTrue();
                // Let the visible lease actually expire while COMMITTED and graph publication
                // remain uncommitted. A single-statement UPDATE claim must now attempt the lock.
                awaitDatabaseLeaseExpired();
                var claimant =
                        workers.submit(
                                () ->
                                        tx.execute(
                                                status -> {
                                                    claimantPid.set(
                                                            jdbc.queryForObject(
                                                                    "SELECT pg_backend_pid()",
                                                                    Integer.class));
                                                    return journal.claim(
                                                                    id, peer, UUID.randomUUID())
                                                            .orElseThrow();
                                                }));
                awaitDatabaseLock(claimantPid, ownerPid);
                assertThat(claimant.isDone()).isFalse();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT revision FROM recovery_graph_publication",
                                        String.class))
                        .isEqualTo(base);
                releaseCommit.countDown();
                assertThat(owner.get(5, TimeUnit.SECONDS)).isTrue();
                var recovered = claimant.get(5, TimeUnit.SECONDS);
                assertThat(recovered.epoch()).isEqualTo(2);
                tx.executeWithoutResult(
                        status -> {
                            assertThat(journal.find(id).orElseThrow().phase())
                                    .isEqualTo(ClusterOperationJournal.Phase.COMMITTED);
                            assertThat(journal.renew(id, original)).isFalse();
                            assertThat(
                                            journal.acknowledge(
                                                    id,
                                                    node,
                                                    original.epoch(),
                                                    ClusterOperationJournal.Phase.COMMITTED,
                                                    target))
                                    .isFalse();
                            assertThat(journal.veto(id, node, original.epoch(), "staleOwner"))
                                    .isFalse();
                            assertThat(
                                            journal.advance(
                                                    id,
                                                    original,
                                                    ClusterOperationJournal.Phase.COMMITTED,
                                                    ClusterOperationJournal.Phase.COMPLETED))
                                    .isFalse();
                        });
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT revision FROM recovery_graph_publication",
                                        String.class))
                        .isEqualTo(target);
            } finally {
                releaseCommit.countDown();
            }
        }
    }

    @Test
    void databaseModuleFenceKeyMatchesTheJavaIeeeCrc32Key() {
        for (String module : List.of("sample", "keeper", "cluster-runtime", "approval-workflow")) {
            var crc = new CRC32();
            crc.update(module.getBytes(StandardCharsets.UTF_8));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT plugin_cluster_runtime_fence_key(?)",
                                    Integer.class,
                                    module))
                    .isEqualTo((int) crc.getValue());
        }
    }

    @Test
    void liveCoordinatorAbortsAndRetiresADepartedPeerWithoutCommittingGraphChanges() {
        begin();
        protocolWrite(
                () ->
                        jdbc.update(
                                "UPDATE platform_plugin_runtime_node "
                                        + "SET heartbeat_at=clock_timestamp()-interval '31 seconds' WHERE node_id=?",
                                peer));
        var callbacks = new RecordingParticipant(false);
        var coordinator = new ClusterRuntimeCoordinator(journal, tx, node, callbacks);
        coordinator.tick();
        coordinator.tick();
        assertThat(phase()).isEqualTo(ClusterOperationJournal.Phase.ABORTED);
        assertThat(participants()).containsExactly(node);
        assertThat(callbacks.prepares.get()).isZero();
        assertThat(callbacks.commits.get()).isZero();
        assertThat(callbacks.aborts.get()).isEqualTo(1);
        assertThat(callbacks.aborted.get()).isEqualTo(1);
        assertThat(callbacks.finishes.get()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision FROM recovery_graph_publication", String.class))
                .isEqualTo(base);
    }

    @Test
    void coldCoordinatorRestoresPrecommitAbortAndFinishesWithoutPreparingOrCommitting() {
        begin();
        expireOriginalNodesAndOwner();
        register(replacement, false);
        var callbacks = new RecordingParticipant(true);
        var coordinator = new ClusterRuntimeCoordinator(journal, tx, replacement, callbacks);
        coordinator.tick();
        coordinator.tick();
        coordinator.tick();
        assertThat(phase()).isEqualTo(ClusterOperationJournal.Phase.ABORTED);
        assertThat(participants()).containsExactly(replacement);
        assertThat(callbacks.recoveries.get()).isEqualTo(1);
        assertThat(callbacks.prepares.get()).isZero();
        assertThat(callbacks.stages.get()).isZero();
        assertThat(callbacks.commits.get()).isZero();
        assertThat(callbacks.applies.get()).isZero();
        assertThat(callbacks.aborts.get()).isEqualTo(1);
        assertThat(callbacks.aborted.get()).isEqualTo(1);
        assertThat(callbacks.finishes.get()).isEqualTo(1);
    }

    @Test
    void coldCoordinatorOnlyAppliesCommittedDecisionAndDoesNotRepublishMetadata() {
        var original = begin().owner();
        prepareToStage(original);
        tx.executeWithoutResult(
                status -> {
                    acknowledgeAll(ClusterOperationJournal.Phase.STAGING, original.epoch());
                    assertThat(
                                    journal.advance(
                                            id,
                                            original,
                                            ClusterOperationJournal.Phase.STAGING,
                                            ClusterOperationJournal.Phase.COMMITTED))
                            .isTrue();
                    jdbc.update("UPDATE recovery_graph_publication SET revision=?", target);
                });
        String originalPublication =
                jdbc.queryForObject(
                        "SELECT xmin::text FROM recovery_graph_publication", String.class);
        expireOriginalNodesAndOwner();
        register(replacement, false);
        var callbacks = new RecordingParticipant(true);
        var coordinator = new ClusterRuntimeCoordinator(journal, tx, replacement, callbacks);
        coordinator.tick();
        coordinator.tick();
        coordinator.tick();
        assertThat(phase()).isEqualTo(ClusterOperationJournal.Phase.COMPLETED);
        assertThat(callbacks.recoveries.get()).isEqualTo(1);
        assertThat(callbacks.commits.get()).isZero();
        assertThat(callbacks.aborts.get()).isZero();
        assertThat(callbacks.aborted.get()).isZero();
        assertThat(callbacks.applies.get()).isEqualTo(1);
        assertThat(callbacks.finishes.get()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT xmin::text FROM recovery_graph_publication", String.class))
                .isEqualTo(originalPublication);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision FROM recovery_graph_publication", String.class))
                .isEqualTo(target);
    }

    @Test
    void pausedOldEpochStageBlocksRetirementAndRollsBackHostSqlWhenItsAckIsRejected()
            throws Exception {
        var original = begin().owner();
        prepareToStage(original);
        var staged = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var callbackPid = new AtomicInteger();
        var callbacks =
                new RecordingParticipant(false) {
                    @Override
                    public void stage(ClusterOperationJournal.Operation operation) {
                        super.stage(operation);
                        callbackPid.set(
                                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        jdbc.update("INSERT INTO recovery_business_records VALUES(3)");
                        staged.countDown();
                        await(resume);
                    }
                };
        var coordinator = new ClusterRuntimeCoordinator(journal, tx, peer, callbacks);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var worker = workers.submit(coordinator::tick);
            try {
                assertThat(staged.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM pg_locks WHERE pid=? "
                                                + "AND locktype='advisory' AND mode='ShareLock' AND granted",
                                        Integer.class,
                                        callbackPid.get()))
                        .isPositive();
                expireOriginalNodesAndOwner();
                register(replacement, false);
                var recovered =
                        tx.execute(
                                status ->
                                        journal.recover(id, replacement, UUID.randomUUID())
                                                .orElseThrow());
                assertThat(phase()).isEqualTo(ClusterOperationJournal.Phase.ABORTING);
                assertThat(retire(recovered, peer)).isFalse();
                assertThat(worker.isDone()).isFalse();
                resume.countDown();
                worker.get(5, TimeUnit.SECONDS);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM recovery_business_records",
                                        Integer.class))
                        .isZero();
                assertThat(callbacks.stages.get()).isEqualTo(1);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT acknowledged_phase IS NULL FROM plugin_cluster_runtime_participant "
                                                + "WHERE operation_id=? AND node_id=?",
                                        Boolean.class,
                                        id,
                                        peer))
                        .isTrue();
                assertThat(retire(recovered, peer)).isTrue();
                coordinator.tick();
                assertThat(callbacks.fenced.get()).isEqualTo(1);
                assertThat(callbacks.stages.get()).isEqualTo(1);
            } finally {
                resume.countDown();
            }
        }
    }

    @Test
    void concurrentColdReplacementNodesAllowOnlyOneRecoveryClaimAndLeaveTheOtherUnadmitted() {
        begin();
        expireOriginalNodesAndOwner();
        UUID contender = UUID.randomUUID();
        register(replacement, false);
        register(contender, false);
        var winnerCallbacks = new RecordingParticipant(true);
        var loserCallbacks = new RecordingParticipant(true);
        var winner = new ClusterRuntimeCoordinator(journal, tx, replacement, winnerCallbacks);
        var loser = new ClusterRuntimeCoordinator(journal, tx, contender, loserCallbacks);
        winner.tick();
        loser.tick();
        assertThat(participants()).contains(replacement).doesNotContain(contender);
        assertThat(loserCallbacks.aborts.get()).isZero();
        assertThat(loserCallbacks.finishes.get()).isZero();
        winner.tick();
        winner.tick();
        loser.tick();
        assertThat(phase()).isEqualTo(ClusterOperationJournal.Phase.ABORTED);
        assertThat(participants()).containsExactly(replacement);
        assertThat(winnerCallbacks.aborts.get()).isEqualTo(1);
        assertThat(winnerCallbacks.finishes.get()).isEqualTo(1);
        assertThat(loserCallbacks.aborts.get()).isZero();
        assertThat(loserCallbacks.finishes.get()).isZero();
        assertThat(loser.hasPendingTerminal()).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT admitted FROM plugin_cluster_runtime_node_capability WHERE node_id=?",
                                Boolean.class,
                                contender))
                .isFalse();
    }

    private java.util.Optional<ClusterOperationJournal.Operation> activeOperation() {
        return tx.execute(status -> journal.active());
    }

    private java.util.Optional<ClusterOperationJournal.Owner> tryRecover(UUID candidate) {
        return tx.execute(status -> journal.recover(id, candidate, UUID.randomUUID()));
    }

    private Set<UUID> participants() {
        return tx.execute(status -> journal.participants(id));
    }

    private boolean retire(ClusterOperationJournal.Owner owner, UUID departed) {
        return Boolean.TRUE.equals(tx.execute(status -> journal.retire(id, owner, departed)));
    }

    private ClusterOperationJournal.Phase phase() {
        return tx.execute(status -> journal.find(id).orElseThrow().phase());
    }

    private ClusterOperationJournal.Operation begin() {
        return tx.execute(
                status ->
                        journal.begin(
                                id,
                                node,
                                UUID.randomUUID(),
                                base,
                                new ClusterOperationJournal.Decision(
                                        UUID.randomUUID(),
                                        ClusterOperationJournal.Kind.INSTALL,
                                        "sample",
                                        target,
                                        Map.of("sample", digest),
                                        List.of("sample"),
                                        List.of(),
                                        List.of()),
                                Set.of(node, peer)));
    }

    private void register(UUID participant, boolean ready) {
        protocolWrite(
                () ->
                        jdbc.update(
                                "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) "
                                        + "VALUES(?,?,false,?)",
                                participant,
                                ready,
                                ready ? base : ""));
    }

    private void protocolWrite(Runnable work) {
        tx.executeWithoutResult(
                status -> {
                    jdbc.queryForObject(
                            "SELECT set_config('lumira.cluster_protocol','2',true)", String.class);
                    work.run();
                });
    }

    private void expireOriginalNodesAndOwner() {
        protocolWrite(
                () ->
                        jdbc.update(
                                "UPDATE platform_plugin_runtime_node SET heartbeat_at=clock_timestamp()-interval '31 seconds' "
                                        + "WHERE node_id IN (?,?)",
                                node,
                                peer));
        jdbc.update(
                "UPDATE plugin_cluster_runtime_operation SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
                id);
    }

    private void assertRetiredNodeCannotReturn(UUID retired) {
        assertThatThrownBy(
                        () ->
                                protocolWrite(
                                        () ->
                                                jdbc.update(
                                                        "INSERT INTO platform_plugin_runtime_node "
                                                                + "(node_id,ready,fixed_graph,graph_revision) VALUES(?,true,false,?) "
                                                                + "ON CONFLICT(node_id) DO UPDATE SET heartbeat_at=clock_timestamp(),ready=true,graph_revision=EXCLUDED.graph_revision",
                                                        retired,
                                                        base)))
                .isInstanceOf(DataAccessException.class);
    }

    private void prepareToStage(ClusterOperationJournal.Owner owner) {
        tx.executeWithoutResult(
                status -> {
                    acknowledgeAll(ClusterOperationJournal.Phase.PREPARING, owner.epoch());
                    assertThat(
                                    journal.advance(
                                            id,
                                            owner,
                                            ClusterOperationJournal.Phase.PREPARING,
                                            ClusterOperationJournal.Phase.DRAINING))
                            .isTrue();
                    acknowledgeAll(ClusterOperationJournal.Phase.DRAINING, owner.epoch());
                    assertThat(
                                    journal.advance(
                                            id,
                                            owner,
                                            ClusterOperationJournal.Phase.DRAINING,
                                            ClusterOperationJournal.Phase.STAGING))
                            .isTrue();
                });
    }

    private void acknowledgeAll(ClusterOperationJournal.Phase phase, long epoch) {
        for (UUID participant : Set.of(node, peer))
            assertThat(journal.acknowledge(id, participant, epoch, phase, base)).isTrue();
    }

    private void awaitDatabaseLock(AtomicInteger blocked, AtomicInteger blocking)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (blocked.get() == 0
                || !Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock' "
                                        + "AND ?=ANY(pg_blocking_pids(pid)))",
                                Boolean.class,
                                blocked.get(),
                                blocking.get()))) {
            assertThat(System.nanoTime())
                    .as("claim must actually wait behind the owner transaction")
                    .isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private void awaitDatabaseLeaseExpired() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT lease_expires_at<=clock_timestamp() "
                                + "FROM plugin_cluster_runtime_operation WHERE id=?",
                        Boolean.class,
                        id))) {
            assertThat(System.nanoTime())
                    .as("the database lease must expire before physical commit")
                    .isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static int backendPid(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static class RecordingParticipant implements ClusterRuntimeCoordinator.Participant {
        final AtomicInteger prepares = new AtomicInteger();
        final AtomicInteger stages = new AtomicInteger();
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger applies = new AtomicInteger();
        final AtomicInteger aborts = new AtomicInteger();
        final AtomicInteger aborted = new AtomicInteger();
        final AtomicInteger finishes = new AtomicInteger();
        final AtomicInteger recoveries = new AtomicInteger();
        final AtomicInteger fenced = new AtomicInteger();
        private final boolean allowRecovery;

        RecordingParticipant(boolean allowRecovery) {
            this.allowRecovery = allowRecovery;
        }

        @Override
        public void prepare(ClusterOperationJournal.Operation operation) {
            prepares.incrementAndGet();
        }

        @Override
        public void drain(ClusterOperationJournal.Operation operation) {}

        @Override
        public void stage(ClusterOperationJournal.Operation operation) {
            stages.incrementAndGet();
        }

        @Override
        public void commit(ClusterOperationJournal.Operation operation) {
            commits.incrementAndGet();
        }

        @Override
        public void apply(ClusterOperationJournal.Operation operation) {
            applies.incrementAndGet();
        }

        @Override
        public void abort(ClusterOperationJournal.Operation operation) {
            aborts.incrementAndGet();
        }

        @Override
        public void aborted(ClusterOperationJournal.Operation operation) {
            aborted.incrementAndGet();
        }

        @Override
        public void finish(ClusterOperationJournal.Operation operation) {
            finishes.incrementAndGet();
        }

        @Override
        public boolean recover(ClusterOperationJournal.Operation operation) {
            recoveries.incrementAndGet();
            return allowRecovery;
        }

        @Override
        public void fenced(ClusterOperationJournal.Operation operation) {
            fenced.incrementAndGet();
        }
    }
}
