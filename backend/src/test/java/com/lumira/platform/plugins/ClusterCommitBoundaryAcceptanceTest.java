package com.lumira.platform.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lumira.platform.TestSchema;
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
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL transactions and termination; participant callbacks use an isolated graph
 * fixture.
 */
@EnabledIfEnvironmentVariable(named = "LUMIRA_CLUSTER_ACCEPTANCE", matches = "1")
@Timeout(40)
class ClusterCommitBoundaryAcceptanceTest {
    private DriverManagerDataSource source;
    private JdbcTemplate admin;
    private JdbcTemplate sql;
    private TransactionTemplate tx;
    private ClusterOperationJournal journal;
    private String schema;
    private final UUID id = UUID.randomUUID();
    private final UUID node = UUID.randomUUID();
    private final UUID peer = UUID.randomUUID();
    private final String base = "a".repeat(64);
    private final String target = "b".repeat(64);

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        assertThat(url)
                .matches(
                        "jdbc:postgresql://127\\.0\\.0\\.1:5432/lumira_qa_cluster_acceptance_[a-f0-9]{12}");
        UUID runToken = UUID.fromString(System.getenv("LUMIRA_QA_RUN_TOKEN"));
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                url,
                                System.getenv("LUMIRA_TEST_DB_USER"),
                                System.getenv("LUMIRA_TEST_DB_PASSWORD")));
        assertThat(
                        admin.queryForObject(
                                "SELECT run_token FROM public.lumira_qa_fixture_guard WHERE id AND purpose='isolated-security-qa'",
                                UUID.class))
                .isEqualTo(runToken);
        schema = "qa_commit_acceptance_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        source =
                new DriverManagerDataSource(
                        url + "?currentSchema=" + schema,
                        System.getenv("LUMIRA_TEST_DB_USER"),
                        System.getenv("LUMIRA_TEST_DB_PASSWORD"));
        sql = new JdbcTemplate(source);
        sql.setQueryTimeout(10);
        tx = transaction(source);
        journal = new ClusterOperationJournal(source, new JsonMapper());
        for (String table :
                List.of(
                        "platform_plugin_runtime_node",
                        "platform_plugin_installation",
                        "platform_module_state")) sql.execute(TestSchema.table(table));
        sql.update(
                "INSERT INTO platform_plugin_installation(module_id,digest,installed,explicit_install) VALUES('unaffected',?,true,true)",
                "d".repeat(64));
        sql.update(
                "INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(?,'unaffected',true,1)",
                UUID.randomUUID());
        Path migrations = Path.of("../plugins/cluster-runtime/migrations");
        var catalog =
                PluginInitializationMigrationRunner.readCatalog("cluster-runtime", migrations);
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(Files.readString(migrations.resolve(catalog.script())));
        }
        sql.execute(
                "CREATE TABLE graph_publication(operation_id uuid PRIMARY KEY, revision text NOT NULL)");
        sql.execute("CREATE TABLE unaffected_business(id integer PRIMARY KEY)");
        tx.executeWithoutResult(
                status -> {
                    sql.queryForObject(
                            "SELECT set_config('lumira.cluster_protocol','2',true)", String.class);
                    for (UUID participant : Set.of(node, peer)) {
                        sql.update(
                                "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) VALUES(?,true,false,?)",
                                participant,
                                base);
                    }
                });
    }

    @AfterEach
    void cleanup() {
        if (schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private static TransactionTemplate transaction(javax.sql.DataSource dataSource) {
        var result = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        result.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        result.setTimeout(25);
        return result;
    }

    private ClusterOperationJournal.Owner begin() {
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
                                                Map.of("sample", "c".repeat(64)),
                                                List.of("sample"),
                                                List.of(),
                                                List.of()),
                                        Set.of(node, peer))
                                .owner());
    }

    private ClusterOperationJournal.Owner stage() {
        var owner = begin();
        for (var phase :
                List.of(
                        ClusterOperationJournal.Phase.PREPARING,
                        ClusterOperationJournal.Phase.DRAINING)) {
            tx.executeWithoutResult(
                    status -> {
                        acknowledgeAll(owner, phase, base);
                        assertThat(
                                        journal.advance(
                                                id,
                                                owner,
                                                phase,
                                                phase == ClusterOperationJournal.Phase.PREPARING
                                                        ? ClusterOperationJournal.Phase.DRAINING
                                                        : ClusterOperationJournal.Phase.STAGING))
                                .isTrue();
                    });
        }
        tx.executeWithoutResult(
                status -> acknowledgeAll(owner, ClusterOperationJournal.Phase.STAGING, base));
        return owner;
    }

    private void acknowledgeAll(
            ClusterOperationJournal.Owner owner,
            ClusterOperationJournal.Phase phase,
            String revision) {
        for (UUID participant : Set.of(node, peer))
            assertThat(journal.acknowledge(id, participant, owner.epoch(), phase, revision))
                    .isTrue();
    }

    private void publish(
            ClusterOperationJournal store, JdbcTemplate jdbc, ClusterOperationJournal.Owner owner) {
        assertThat(
                        store.advance(
                                id,
                                owner,
                                ClusterOperationJournal.Phase.STAGING,
                                ClusterOperationJournal.Phase.COMMITTED))
                .isTrue();
        jdbc.update("INSERT INTO graph_publication VALUES(?,?)", id, target);
    }

    private ClusterOperationJournal.Operation operation() {
        return tx.execute(status -> journal.find(id).orElseThrow());
    }

    private Set<UUID> participants() {
        return tx.execute(status -> journal.participants(id));
    }

    private int publications() {
        return sql.queryForObject("SELECT count(*) FROM graph_publication", Integer.class);
    }

    private void expireLease() {
        sql.update(
                "UPDATE plugin_cluster_runtime_operation SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
                id);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).as("fixture latch released").isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void eventually(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as(message).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private void waitForDatabaseLock(int pid) throws Exception {
        eventually(
                () ->
                        Boolean.TRUE.equals(
                                sql.queryForObject(
                                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND datname=current_database() AND wait_event_type='Lock')",
                                        Boolean.class,
                                        pid)),
                "takeover must actually wait on the uncommitted owner's PostgreSQL row lock");
    }

    private void terminateOwnSession(int pid) {
        assertThat(
                        sql.queryForObject(
                                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE pid=? AND datname=current_database() AND usename=current_user AND pid<>pg_backend_pid()",
                                Boolean.class,
                                pid))
                .isTrue();
    }

    @Test
    void expiredLeaseCannotOvertakePhysicalCommitAndRecoveryOnlyAppliesTheCommittedDecision()
            throws Exception {
        var owner = stage();
        sql.update(
                "UPDATE plugin_cluster_runtime_operation SET lease_expires_at=clock_timestamp()+interval '2 seconds' WHERE id=?",
                id);
        var beforeCommit = new CountDownLatch(1);
        var releaseCommit = new CountDownLatch(1);
        var claimantStarted = new CountDownLatch(1);
        var claimantPid = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var original =
                    workers.submit(
                            () ->
                                    tx.executeWithoutResult(
                                            status -> {
                                                publish(journal, sql, owner);
                                                beforeCommit.countDown();
                                                await(releaseCommit);
                                            }));
            try {
                await(beforeCommit);
                eventually(
                        () ->
                                sql.queryForObject(
                                        "SELECT lease_expires_at<=clock_timestamp() FROM plugin_cluster_runtime_operation WHERE id=?",
                                        Boolean.class,
                                        id),
                        "the database clock expires the lease before physical commit");
                var takeover =
                        workers.submit(
                                () ->
                                        tx.execute(
                                                status -> {
                                                    claimantPid.set(
                                                            sql.queryForObject(
                                                                    "SELECT pg_backend_pid()",
                                                                    Integer.class));
                                                    claimantStarted.countDown();
                                                    var claimed =
                                                            journal.claim(
                                                                            id,
                                                                            peer,
                                                                            UUID.randomUUID())
                                                                    .orElseThrow();
                                                    assertThat(
                                                                    journal.find(id)
                                                                            .orElseThrow()
                                                                            .phase())
                                                            .isEqualTo(
                                                                    ClusterOperationJournal.Phase
                                                                            .COMMITTED);
                                                    assertThat(publications()).isEqualTo(1);
                                                    return claimed;
                                                }));
                await(claimantStarted);
                waitForDatabaseLock(claimantPid.get());
                assertThat(takeover.isDone()).isFalse();
                assertThat(publications()).isZero();
                sql.update("INSERT INTO unaffected_business VALUES(1)");
                assertThat(
                                sql.queryForObject(
                                        "SELECT count(*) FROM unaffected_business", Integer.class))
                        .isEqualTo(1);
                releaseCommit.countDown();
                original.get(10, TimeUnit.SECONDS);
                var recoveredOwner = takeover.get(10, TimeUnit.SECONDS);
                assertThat(recoveredOwner.epoch()).isEqualTo(owner.epoch() + 1);
                tx.executeWithoutResult(
                        status -> {
                            assertThat(journal.renew(id, owner)).isFalse();
                            assertThat(
                                            journal.acknowledge(
                                                    id,
                                                    node,
                                                    owner.epoch(),
                                                    ClusterOperationJournal.Phase.COMMITTED,
                                                    target))
                                    .isFalse();
                        });
                completeRecovery(false);
                assertThat(publications()).isEqualTo(1);
                System.out.println(
                        "ACCEPTANCE physical-commit: expired database lease, proven blocked claimant, one publication, roll-forward only");
            } finally {
                releaseCommit.countDown();
            }
        }
    }

    @Test
    void terminatingTheOwnerBeforePhysicalCommitRollsBackGraphAndDecisionTogether()
            throws Exception {
        var owner = stage();
        var beforeCommit = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pid = new AtomicInteger();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var original =
                    workers.submit(
                            () ->
                                    tx.executeWithoutResult(
                                            status -> {
                                                pid.set(
                                                        sql.queryForObject(
                                                                "SELECT pg_backend_pid()",
                                                                Integer.class));
                                                publish(journal, sql, owner);
                                                beforeCommit.countDown();
                                                await(release);
                                            }));
            try {
                await(beforeCommit);
                assertThat(publications()).isZero();
                terminateOwnSession(pid.get());
                release.countDown();
                assertThatThrownBy(() -> original.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class);
                assertThat(operation().phase()).isEqualTo(ClusterOperationJournal.Phase.STAGING);
                assertThat(publications()).isZero();
                expireLease();
                completeRecovery(true);
                assertThat(publications()).isZero();
                System.out.println(
                        "ACCEPTANCE precommit-disconnect: real PostgreSQL backend termination, paired rollback, coordinator abort");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void terminatingTheOwnerAfterCommitCannotReverseOrRepeatItsPublication() throws Exception {
        var owner = stage();
        try (var connection = source.getConnection()) {
            var single = new SingleConnectionDataSource(connection, true);
            var ownerSql = new JdbcTemplate(single);
            var ownerJournal = new ClusterOperationJournal(single, new JsonMapper());
            int pid = ownerSql.queryForObject("SELECT pg_backend_pid()", Integer.class);
            transaction(single)
                    .executeWithoutResult(status -> publish(ownerJournal, ownerSql, owner));
            assertThat(operation().phase()).isEqualTo(ClusterOperationJournal.Phase.COMMITTED);
            assertThat(publications()).isEqualTo(1);
            terminateOwnSession(pid);
            expireLease();
            completeRecovery(false);
            assertThat(publications()).isEqualTo(1);
            assertThat(
                            sql.queryForObject(
                                    "SELECT revision FROM graph_publication WHERE operation_id=?",
                                    String.class,
                                    id))
                    .isEqualTo(target);
            System.out.println(
                    "ACCEPTANCE postcommit-disconnect: real PostgreSQL backend termination, committed graph retained exactly once");
        }
    }

    @Test
    void delayedOldEpochStageCannotAcknowledgeOrCommitAfterPeerTakeover() throws Exception {
        var owner = stage();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var oldWork =
                new RecordingParticipant() {
                    @Override
                    public void stage(ClusterOperationJournal.Operation operation) {
                        entered.countDown();
                        await(release);
                    }
                };
        var oldCoordinator = new ClusterRuntimeCoordinator(journal, tx, node, oldWork);
        var replacementWork = new RecordingParticipant();
        var replacement = new ClusterRuntimeCoordinator(journal, tx, peer, replacementWork);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var delayed = workers.submit(oldCoordinator::tick);
            try {
                await(entered);
                expireLease();
                replacement.tick();
                assertThat(operation().owner().epoch()).isEqualTo(owner.epoch() + 1);
                assertThat(operation().phase()).isEqualTo(ClusterOperationJournal.Phase.ABORTING);
                assertThat(operation().failureCode()).isEqualTo("pluginOperationInterrupted");
                release.countDown();
                delayed.get(10, TimeUnit.SECONDS);
                assertThat(
                                sql.queryForObject(
                                        "SELECT acknowledged_phase FROM plugin_cluster_runtime_participant WHERE operation_id=? AND node_id=?",
                                        String.class,
                                        id,
                                        node))
                        .isNull();
                oldCoordinator.tick();
                replacement.tick();
                oldCoordinator.tick();
                replacement.tick();
                assertThat(operation().phase()).isEqualTo(ClusterOperationJournal.Phase.ABORTED);
                assertThat(operation().failureCode()).isEqualTo("pluginOperationInterrupted");
                assertThat(oldWork.commits + replacementWork.commits).isZero();
                assertThat(oldWork.applies + replacementWork.applies).isZero();
                assertThat(oldWork.aborts).isEqualTo(1);
                assertThat(publications()).isZero();
                System.out.println(
                        "ACCEPTANCE delayed-thread: old callback resumed after new epoch, stale ACK refused, cleanup completed");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void replacementNodeRecoversAnUncommittedOperationWithoutTheOriginalNodeIds() {
        stage();
        replaceAllParticipantsAndRecover(true);
    }

    @Test
    void replacementNodeRollsForwardACommittedOperationWithoutRepeatingGraphWrites() {
        var owner = stage();
        tx.executeWithoutResult(status -> publish(journal, sql, owner));
        replaceAllParticipantsAndRecover(false);
    }

    private void replaceAllParticipantsAndRecover(boolean abort) {
        UUID replacement = registerReplacementAfterExpiry();
        var work = new RecordingParticipant();
        var coordinator = new ClusterRuntimeCoordinator(journal, tx, replacement, work);
        for (int pass = 0; pass < 5; pass++) coordinator.tick();
        assertThat(operation().phase())
                .as(
                        "a registered cold replacement must recover after every frozen process UUID disappears")
                .isEqualTo(
                        abort
                                ? ClusterOperationJournal.Phase.ABORTED
                                : ClusterOperationJournal.Phase.COMPLETED);
        assertThat(work.recoveries).isPositive();
        assertThat(work.commits).isZero();
        assertThat(publications()).isEqualTo(abort ? 0 : 1);
        assertThat(work.aborts).isEqualTo(abort ? 1 : 0);
        assertThat(work.applies).isEqualTo(abort ? 0 : 1);
        assertThat(work.finishes).isEqualTo(1);
    }

    private UUID registerReplacementAfterExpiry() {
        expireLease();
        UUID replacement = UUID.randomUUID();
        tx.executeWithoutResult(
                status -> {
                    sql.queryForObject(
                            "SELECT set_config('lumira.cluster_protocol','2',true)", String.class);
                    sql.update(
                            "UPDATE platform_plugin_runtime_node SET ready=false,heartbeat_at=clock_timestamp()-interval '31 seconds' WHERE node_id IN (?,?)",
                            node,
                            peer);
                    sql.update(
                            "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) VALUES(?,false,false,'')",
                            replacement);
                });
        return replacement;
    }

    @Test
    void coldNodeThatHasNotPreparedItsRuntimeCannotClaimOrPublish() {
        stage();
        UUID replacement = registerReplacementAfterExpiry();
        var before = operation();
        var beforeParticipants = participants();
        var unprepared =
                new RecordingParticipant() {
                    @Override
                    public boolean recover(ClusterOperationJournal.Operation operation) {
                        recoveries++;
                        return false;
                    }
                };
        var coordinator = new ClusterRuntimeCoordinator(journal, tx, replacement, unprepared);
        for (int pass = 0; pass < 3; pass++) coordinator.tick();
        assertThat(unprepared.recoveries).isPositive();
        assertThat(operation()).isEqualTo(before);
        assertThat(participants()).isEqualTo(beforeParticipants);
        assertThat(
                        unprepared.aborts
                                + unprepared.applies
                                + unprepared.commits
                                + unprepared.finishes)
                .isZero();
        assertThat(publications()).isZero();
    }

    @Test
    void retirementWaitsForAnUnrelatedPhysicalBusinessTransactionAndRevokesTheOldNodeIds()
            throws Exception {
        stage();
        var fenced = new PluginFencedDataSource(source);
        try (var connection = fenced.getConnection()) {
            connection.setAutoCommit(false);
            try (var scope =
                            PluginInvocationContext.open(
                                    new PluginInvocationContext.Owner(
                                            "unaffected", "d".repeat(64), 1, node));
                    var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO unaffected_business VALUES(42)");
            }
            UUID replacement = registerReplacementAfterExpiry();
            var work = new RecordingParticipant();
            var coordinator = new ClusterRuntimeCoordinator(journal, tx, replacement, work);
            for (int pass = 0; pass < 3; pass++) coordinator.tick();
            assertThat(operation().phase()).isEqualTo(ClusterOperationJournal.Phase.ABORTING);
            assertThat(participants()).contains(node, peer, replacement);
            assertThat(
                            sql.queryForObject(
                                    "SELECT count(*) FROM unaffected_business", Integer.class))
                    .isZero();
            assertThat(work.finishes).isZero();
            connection.rollback();
            for (int pass = 0; pass < 5; pass++) coordinator.tick();
            assertThat(operation().phase()).isEqualTo(ClusterOperationJournal.Phase.ABORTED);
            assertThat(participants()).containsExactly(replacement);
            assertThat(work.finishes).isEqualTo(1);
            assertThatThrownBy(
                            () ->
                                    tx.executeWithoutResult(
                                            status -> {
                                                sql.queryForObject(
                                                        "SELECT set_config('lumira.cluster_protocol','2',true)",
                                                        String.class);
                                                sql.update(
                                                        "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) VALUES(?,false,false,?)",
                                                        node,
                                                        base);
                                            }))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(publications()).isZero();
            System.out.println(
                    "ACCEPTANCE retirement: unrelated physical SQL prevents retirement; rollback releases it; retired UUID cannot reenter");
        }
    }

    private void completeRecovery(boolean abort) {
        var former = new RecordingParticipant();
        var survivor = new RecordingParticipant();
        var first = new ClusterRuntimeCoordinator(journal, tx, node, former);
        var second = new ClusterRuntimeCoordinator(journal, tx, peer, survivor);
        for (int pass = 0; pass < 4; pass++) {
            second.tick();
            first.tick();
        }
        assertThat(operation().phase())
                .isEqualTo(
                        abort
                                ? ClusterOperationJournal.Phase.ABORTED
                                : ClusterOperationJournal.Phase.COMPLETED);
        assertThat(former.commits + survivor.commits).isZero();
        assertThat(former.aborts + survivor.aborts).isEqualTo(abort ? 2 : 0);
        assertThat(former.applies + survivor.applies).isEqualTo(abort ? 0 : 2);
        assertThat(former.finishes + survivor.finishes).isEqualTo(2);
    }

    private static class RecordingParticipant implements ClusterRuntimeCoordinator.Participant {
        int commits;
        int applies;
        int aborts;
        int finishes;
        int recoveries;

        // Forward-compatible with the recovery participant hook; this fixture owns no live handles.
        public boolean recover(ClusterOperationJournal.Operation operation) {
            recoveries++;
            return true;
        }

        @Override
        public void prepare(ClusterOperationJournal.Operation operation) {}

        @Override
        public void drain(ClusterOperationJournal.Operation operation) {}

        @Override
        public void stage(ClusterOperationJournal.Operation operation) {}

        @Override
        public void commit(ClusterOperationJournal.Operation operation) {
            commits++;
        }

        @Override
        public void apply(ClusterOperationJournal.Operation operation) {
            applies++;
        }

        @Override
        public void abort(ClusterOperationJournal.Operation operation) {
            aborts++;
        }

        @Override
        public void aborted(ClusterOperationJournal.Operation operation) {}

        @Override
        public void finish(ClusterOperationJournal.Operation operation) {
            finishes++;
        }
    }
}
