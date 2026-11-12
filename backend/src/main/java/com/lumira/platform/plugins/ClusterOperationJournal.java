package com.lumira.platform.plugins;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Durable control-plane state supplied by the optional cluster-runtime infrastructure plugin. Uses
 * the host transaction: graph publication and COMMITTED must never use separate connections. This
 * store does not authorize, drain, migrate or publish a graph on its caller's behalf.
 */
final class ClusterOperationJournal {
    enum Phase {
        PREPARING,
        DRAINING,
        STAGING,
        COMMITTED,
        COMPLETED,
        ABORTING,
        ABORTED
    }

    enum Kind {
        INSTALL,
        UNINSTALL
    }

    record Owner(UUID nodeId, UUID token, long epoch) {
        Owner {
            Objects.requireNonNull(nodeId);
            Objects.requireNonNull(token);
            if (epoch < 1) throw new IllegalArgumentException("invalidClusterOwnerEpoch");
        }
    }

    /** Original account UUID survives renames, deletion and username reuse during takeover. */
    record Decision(
            UUID actorId,
            Kind kind,
            String moduleId,
            String targetRevision,
            Map<String, String> digests,
            List<String> install,
            List<String> enable,
            List<String> remove) {
        Decision {
            Objects.requireNonNull(actorId);
            Objects.requireNonNull(kind);
            module(moduleId);
            revision(targetRevision);
            digests = Map.copyOf(digests);
            install = List.copyOf(install);
            enable = List.copyOf(enable);
            remove = List.copyOf(remove);
            digests.forEach(
                    (id, digest) -> {
                        module(id);
                        revision(digest);
                    });
            for (var ids : List.of(install, enable, remove)) {
                if (Set.copyOf(ids).size() != ids.size())
                    throw new IllegalArgumentException("duplicateClusterPlanModule");
                ids.forEach(ClusterOperationJournal::module);
            }
            if (kind == Kind.UNINSTALL) {
                // Dependency detachment is a separate explicit action; never cascade removal.
                if (!remove.equals(List.of(moduleId)) || !install.isEmpty() || !enable.isEmpty())
                    throw new IllegalArgumentException("invalidClusterRemovalPlan");
            } else if (!remove.isEmpty()
                    || !digests.keySet().containsAll(install)
                    || !digests.containsKey(moduleId)) {
                throw new IllegalArgumentException("invalidClusterInstallPlan");
            }
        }
    }

    record Operation(
            UUID id,
            Owner owner,
            Instant leaseExpiresAt,
            Phase phase,
            String baseRevision,
            Decision decision,
            Instant phaseStartedAt,
            String failureCode) {}

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    ClusterOperationJournal(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = Objects.requireNonNull(dataSource);
        jdbc = JdbcClient.create(dataSource);
        this.mapper = Objects.requireNonNull(mapper);
    }

    /** Legacy single-node writers must not interleave with an unfinished durable decision. */
    boolean blocksGraphChange() {
        if (!jdbc.sql("SELECT to_regclass('plugin_cluster_runtime_operation') IS NOT NULL")
                .query(Boolean.class)
                .single()) return false;
        return jdbc.sql(
                        """
                SELECT EXISTS(SELECT 1 FROM plugin_cluster_runtime_operation
                  WHERE phase NOT IN ('COMPLETED','ABORTED'))
                """)
                .query(Boolean.class)
                .single();
    }

    boolean protocolReady() {
        return jdbc.sql(
                        "SELECT to_regprocedure('plugin_cluster_runtime_enter(uuid,uuid,bigint,text)') IS NOT NULL")
                .query(Boolean.class)
                .single();
    }

    /** Retained through the participant callback and its ACK, including independent migrations. */
    boolean enter(Operation operation, UUID nodeId) {
        requireTransaction();
        return jdbc.sql("SELECT plugin_cluster_runtime_enter(:op,:node,:epoch,:phase)")
                .param("op", operation.id())
                .param("node", nodeId)
                .param("epoch", operation.owner().epoch())
                .param("phase", operation.phase().name())
                .query(Boolean.class)
                .single();
    }

    /**
     * Cold replacement is opt-in; it can only abort precommit or roll forward a committed graph.
     */
    Optional<Owner> recover(UUID id, UUID nodeId, UUID token) {
        requireTransaction();
        return jdbc.sql("SELECT plugin_cluster_runtime_recover(:op,:node,:token)")
                .param("op", id)
                .param("node", nodeId)
                .param("token", token)
                .query((rs, row) -> rs.getObject(1, Long.class))
                .optional()
                .map(epoch -> new Owner(nodeId, token, epoch));
    }

    /**
     * Permanent revocation and physical drain proof are committed in this same host transaction.
     */
    boolean retire(UUID id, Owner owner, UUID departed) {
        requireTransaction();
        return owned(
                        "SELECT plugin_cluster_runtime_retire(:op,:node,:token,:epoch,:departed)",
                        id,
                        owner)
                .param("departed", departed)
                .query(Boolean.class)
                .single();
    }

    boolean retired(UUID nodeId) {
        requireTransaction();
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM plugin_cluster_runtime_node_revocation WHERE node_id=:node)")
                .param("node", nodeId)
                .query(Boolean.class)
                .single();
    }

    boolean hasDepartedParticipants(UUID id) {
        requireTransaction();
        return jdbc.sql(
                        """
                SELECT EXISTS(SELECT 1 FROM plugin_cluster_runtime_participant p
                  LEFT JOIN platform_plugin_runtime_node n ON n.node_id=p.node_id
                  WHERE p.operation_id=:id AND p.retired_at IS NULL
                    AND (n.node_id IS NULL OR n.heartbeat_at<=clock_timestamp()-interval '30 seconds'))
                """)
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    /**
     * Serialize terminal publication against creation of the next operation, without peer waits.
     */
    boolean enterTerminal(Operation operation, UUID nodeId) {
        requireTransaction();
        jdbc.sql("SELECT id FROM plugin_cluster_runtime_activation WHERE id FOR SHARE")
                .query(Boolean.class)
                .single();
        if (operation.phase() != Phase.ABORTED && operation.phase() != Phase.COMPLETED)
            return false;
        var pending = active();
        if (pending.isPresent()) {
            // This serialized local lane has not participated in the successor yet. Lock its row
            // so publication cannot race its decision or takeover; graph validation stays in
            // Runtime.
            jdbc.sql("SELECT id FROM plugin_cluster_runtime_operation WHERE id=:id FOR UPDATE")
                    .param("id", pending.get().id())
                    .query(UUID.class)
                    .single();
        }
        return enter(operation, nodeId);
    }

    boolean veto(UUID id, UUID nodeId, long epoch, String code) {
        requireTransaction();
        return jdbc.sql("SELECT plugin_cluster_runtime_veto(:op,:node,:epoch,:code)")
                .param("op", id)
                .param("node", nodeId)
                .param("epoch", epoch)
                .param("code", code)
                .query(Boolean.class)
                .single();
    }

    Operation begin(
            UUID id,
            UUID nodeId,
            UUID token,
            String baseRevision,
            Decision decision,
            Set<UUID> participants) {
        return begin(id, nodeId, token, baseRevision, decision, participants, false);
    }

    Operation begin(
            UUID id,
            UUID nodeId,
            UUID token,
            String baseRevision,
            Decision decision,
            Set<UUID> participants,
            boolean alreadyActivated) {
        requireTransaction();
        Objects.requireNonNull(id);
        var owner = new Owner(nodeId, token, 1);
        revision(baseRevision);
        Objects.requireNonNull(decision);
        participants = Set.copyOf(participants);
        if (!participants.contains(nodeId))
            throw new IllegalArgumentException("clusterOwnerMustParticipate");
        if (!alreadyActivated && !activate(baseRevision).equals(participants))
            throw new IllegalArgumentException("clusterParticipantsChanged");
        jdbc.sql(
                        """
                INSERT INTO plugin_cluster_runtime_operation
                (id,owner_node_id,owner_token,owner_epoch,lease_expires_at,phase,base_revision,target)
                VALUES(:id,:node,:token,1,clock_timestamp()+interval '45 seconds',
                  'PREPARING',:base,CAST(:target AS jsonb))
                """)
                .param("id", id)
                .param("node", owner.nodeId())
                .param("token", owner.token())
                .param("base", baseRevision)
                .param("target", mapper.writeValueAsString(decision))
                .update();
        for (UUID participant : participants) {
            jdbc.sql(
                            """
                    INSERT INTO plugin_cluster_runtime_participant(operation_id,node_id,owner_epoch,execution_fenced)
                    VALUES(:op,:node,1,true)
                    """)
                    .param("op", id)
                    .param("node", participant)
                    .update();
        }
        return find(id).orElseThrow();
    }

    /** The activation row freezes membership against concurrent registration and heartbeat. */
    Set<UUID> activate(String baseRevision) {
        requireTransaction();
        revision(baseRevision);
        return Set.copyOf(
                jdbc.sql("SELECT unnest(plugin_cluster_runtime_activate(:base))")
                        .param("base", baseRevision)
                        .query(UUID.class)
                        .list());
    }

    boolean deactivate() {
        requireTransaction();
        return jdbc.sql("SELECT plugin_cluster_runtime_deactivate()").query(Boolean.class).single();
    }

    Optional<Operation> active() {
        requireTransaction();
        return mapped(query("WHERE phase NOT IN ('COMPLETED','ABORTED')")).optional();
    }

    Optional<Operation> find(UUID id) {
        requireTransaction();
        return mapped(query("WHERE id=:id").param("id", Objects.requireNonNull(id))).optional();
    }

    Set<UUID> participants(UUID id) {
        requireTransaction();
        return Set.copyOf(
                jdbc.sql(
                                """
                SELECT node_id FROM plugin_cluster_runtime_participant WHERE operation_id=:id AND retired_at IS NULL
                """)
                        .param("id", Objects.requireNonNull(id))
                        .query(UUID.class)
                        .list());
    }

    boolean renew(UUID id, Owner owner) {
        requireTransaction();
        return owned("SELECT plugin_cluster_runtime_renew(:op,:node,:token,:epoch)", id, owner)
                .query(Boolean.class)
                .single();
    }

    Optional<Owner> claim(UUID id, UUID nodeId, UUID token) {
        requireTransaction();
        Objects.requireNonNull(nodeId);
        Objects.requireNonNull(token);
        // Existing participants use claim; cold replacements require the stricter recover gate.
        if (!participants(id).contains(nodeId))
            throw new IllegalArgumentException("clusterRecoveryNodeNotParticipant");
        var epoch =
                jdbc.sql("SELECT plugin_cluster_runtime_claim(:op,:node,:token)")
                        .param("op", id)
                        .param("node", nodeId)
                        .param("token", token)
                        .query((rs, row) -> rs.getObject(1, Long.class))
                        .optional();
        return epoch.map(value -> new Owner(nodeId, token, value));
    }

    boolean acknowledge(UUID id, UUID nodeId, long epoch, Phase phase, String observedRevision) {
        requireTransaction();
        revision(observedRevision);
        var operation = find(id).orElseThrow();
        String expected =
                phase == Phase.COMMITTED || phase == Phase.COMPLETED
                        ? operation.decision().targetRevision()
                        : operation.baseRevision();
        if (!expected.equals(observedRevision)) return false;
        return jdbc.sql("SELECT plugin_cluster_runtime_ack(:op,:node,:epoch,:phase,:revision)")
                .param("op", id)
                .param("node", Objects.requireNonNull(nodeId))
                .param("epoch", epoch)
                .param("phase", Objects.requireNonNull(phase).name())
                .param("revision", observedRevision)
                .query(Boolean.class)
                .single();
    }

    boolean advance(UUID id, Owner owner, Phase expected, Phase next) {
        requireTransaction();
        return owned(
                        "SELECT plugin_cluster_runtime_advance(:op,:node,:token,:epoch,:expected,:next)",
                        id,
                        owner)
                .param("expected", Objects.requireNonNull(expected).name())
                .param("next", Objects.requireNonNull(next).name())
                .query(Boolean.class)
                .single();
    }

    private JdbcClient.StatementSpec owned(String sql, UUID id, Owner owner) {
        Objects.requireNonNull(owner);
        return jdbc.sql(sql)
                .param("op", Objects.requireNonNull(id))
                .param("node", owner.nodeId())
                .param("token", owner.token())
                .param("epoch", owner.epoch());
    }

    private JdbcClient.StatementSpec query(String predicate) {
        return jdbc.sql(
                """
                SELECT id,owner_node_id,owner_token,owner_epoch,lease_expires_at,phase,
                  base_revision,target::text,phase_started_at,failure_code FROM plugin_cluster_runtime_operation
                """
                        + predicate);
    }

    private JdbcClient.MappedQuerySpec<Operation> mapped(JdbcClient.StatementSpec statement) {
        return statement.query(
                (rs, row) ->
                        new Operation(
                                rs.getObject("id", UUID.class),
                                        new Owner(
                                                rs.getObject("owner_node_id", UUID.class),
                                                rs.getObject("owner_token", UUID.class),
                                                rs.getLong("owner_epoch")),
                                rs.getTimestamp("lease_expires_at").toInstant(),
                                        Phase.valueOf(rs.getString("phase")),
                                rs.getString("base_revision"),
                                        mapper.readValue(rs.getString("target"), Decision.class),
                                rs.getTimestamp("phase_started_at").toInstant(),
                                        rs.getString("failure_code")));
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource)
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Objects.equals(
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        Connection.TRANSACTION_READ_COMMITTED))
            throw new IllegalStateException("clusterJournalRequiresReadCommittedHostTransaction");
        var connection = DataSourceUtils.getConnection(dataSource);
        try {
            if (connection.getAutoCommit()
                    || connection.isReadOnly()
                    || connection.getTransactionIsolation()
                            != Connection.TRANSACTION_READ_COMMITTED)
                throw new IllegalStateException(
                        "clusterJournalRequiresReadCommittedHostTransaction");
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException("clusterJournalTransactionUnavailable", error);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private static void revision(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("invalidClusterRevision");
    }

    private static void module(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("invalidClusterModule");
    }
}
