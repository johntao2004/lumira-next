package com.lumira.platform.plugins;

import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives a durable decision; participant work never holds a transaction while waiting for peers.
 */
final class ClusterRuntimeCoordinator {
    interface Participant {
        void prepare(ClusterOperationJournal.Operation operation) throws Exception;

        void drain(ClusterOperationJournal.Operation operation) throws Exception;

        void stage(ClusterOperationJournal.Operation operation) throws Exception;

        /** Runs in the SAME host transaction as the COMMITTED journal transition. */
        void commit(ClusterOperationJournal.Operation operation) throws Exception;

        void apply(ClusterOperationJournal.Operation operation) throws Exception;

        void abort(ClusterOperationJournal.Operation operation) throws Exception;

        void aborted(ClusterOperationJournal.Operation operation) throws Exception;

        void finish(ClusterOperationJournal.Operation operation) throws Exception;

        /**
         * Opt in only after cold-node artifact initialization. Must not publish readiness or mutate
         * graph metadata. apply/abort must restore the full committed graph on this cold node.
         */
        default boolean recover(ClusterOperationJournal.Operation operation) throws Exception {
            return false;
        }

        /** Revoke local request admission permanently when this node UUID has been retired. */
        default void fenced(ClusterOperationJournal.Operation operation) throws Exception {}
    }

    private final ClusterOperationJournal journal;
    private final TransactionTemplate tx;
    private final UUID nodeId;
    private final Participant participant;
    private ClusterOperationJournal.Operation local;
    private ClusterOperationJournal.Phase acknowledged;
    private long acknowledgedEpoch;

    ClusterRuntimeCoordinator(
            ClusterOperationJournal journal,
            TransactionTemplate tx,
            UUID nodeId,
            Participant participant) {
        this.journal = Objects.requireNonNull(journal);
        this.tx = Objects.requireNonNull(tx);
        this.nodeId = Objects.requireNonNull(nodeId);
        this.participant = Objects.requireNonNull(participant);
    }

    /**
     * Independent heartbeat lane: a slow migration must not prevent the owner renewing its lease.
     */
    void renewLease() {
        if (!journal.protocolReady()) return;
        tx.executeWithoutResult(
                status ->
                        journal.active()
                                .ifPresent(
                                        operation -> {
                                            if (operation.owner().nodeId().equals(nodeId))
                                                journal.renew(operation.id(), operation.owner());
                                        }));
    }

    boolean hasPendingTerminal() {
        return local != null;
    }

    void tick() {
        if (!journal.protocolReady()) return;
        ClusterOperationJournal.Operation operation =
                tx.execute(status -> journal.active().orElse(null));
        if (operation == null) {
            if (local != null) {
                var terminal = tx.execute(status -> journal.find(local.id()).orElseThrow());
                try {
                    if (finish(terminal)) {
                        local = null;
                        acknowledged = null;
                        acknowledgedEpoch = 0;
                    }
                } catch (Exception failure) {
                    log(terminal, failure);
                }
            }
            return;
        }
        UUID id = operation.id();
        if (local != null && !local.id().equals(id)) {
            var previous = tx.execute(status -> journal.find(local.id()).orElseThrow());
            try {
                if (!finish(previous)) return;
                local = null;
                acknowledged = null;
                acknowledgedEpoch = 0;
            } catch (Exception failure) {
                log(previous, failure);
                return;
            }
        }
        try {
            if (tx.execute(status -> journal.retired(nodeId))) {
                participant.fenced(operation);
                return;
            }
            if (!tx.execute(status -> journal.participants(id).contains(nodeId))) {
                if (!participant.recover(operation)) return;
                if (tx.execute(status -> journal.recover(id, nodeId, UUID.randomUUID())).isEmpty())
                    return;
                operation = tx.execute(status -> journal.find(id).orElseThrow());
            }
            operation = ownOrObserve(operation);
            if (operation == null) return;
            if (operation.owner().nodeId().equals(nodeId)
                    && tx.execute(status -> journal.hasDepartedParticipants(id))) {
                var observed = operation;
                tx.executeWithoutResult(
                        status ->
                                journal.veto(
                                        id,
                                        nodeId,
                                        observed.owner().epoch(),
                                        "pluginOperationInterrupted"));
                operation = tx.execute(status -> journal.find(id).orElseThrow());
            }
            if (local == null || !local.id().equals(id)) {
                acknowledged = null;
                acknowledgedEpoch = 0;
            }
            local = operation;
            var phase = operation.phase();
            if (acknowledged != phase || acknowledgedEpoch != operation.owner().epoch()) {
                var observed = operation;
                String revision =
                        phase == ClusterOperationJournal.Phase.COMMITTED
                                ? operation.decision().targetRevision()
                                : operation.baseRevision();
                if (!tx.execute(
                        status -> {
                            if (!journal.enter(observed, nodeId)) return false;
                            try {
                                switch (phase) {
                                    case PREPARING -> participant.prepare(observed);
                                    case DRAINING -> participant.drain(observed);
                                    case STAGING -> participant.stage(observed);
                                    case COMMITTED -> participant.apply(observed);
                                    case ABORTING -> participant.abort(observed);
                                    default ->
                                            throw new IllegalStateException(
                                                    "invalidActiveClusterPhase");
                                }
                            } catch (Exception failure) {
                                throw new IllegalStateException(
                                        "pluginClusterParticipantFailed", failure);
                            }
                            boolean accepted =
                                    journal.acknowledge(
                                            id, nodeId, observed.owner().epoch(), phase, revision);
                            if (!accepted) status.setRollbackOnly();
                            return accepted;
                        })) return;
                acknowledged = phase;
                acknowledgedEpoch = operation.owner().epoch();
            }
            if (operation.owner().nodeId().equals(nodeId)) {
                retireDeparted(operation);
                advance(operation);
            }
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (operation != null) {
                var failed = operation;
                try {
                    tx.executeWithoutResult(
                            status ->
                                    journal.veto(
                                            id,
                                            nodeId,
                                            failed.owner().epoch(),
                                            "pluginStartFailed"));
                } catch (RuntimeException unavailable) {
                    failure.addSuppressed(unavailable);
                }
                log(operation, failure);
            }
        }
    }

    private boolean finish(ClusterOperationJournal.Operation operation) {
        return tx.execute(
                status -> {
                    if (journal.retired(nodeId)) {
                        try {
                            participant.fenced(operation);
                        } catch (Exception failure) {
                            throw new IllegalStateException("pluginClusterFenceFailed", failure);
                        }
                        return true;
                    }
                    if (!journal.enterTerminal(operation, nodeId)) return false;
                    try {
                        participant.finish(operation);
                    } catch (Exception failure) {
                        throw new IllegalStateException("pluginClusterFinishFailed", failure);
                    }
                    return true;
                });
    }

    private void retireDeparted(ClusterOperationJournal.Operation operation) {
        if (operation.phase() != ClusterOperationJournal.Phase.ABORTING
                && operation.phase() != ClusterOperationJournal.Phase.COMMITTED) return;
        var nodes = tx.execute(status -> journal.participants(operation.id()));
        for (UUID departed : nodes) {
            if (!departed.equals(nodeId))
                tx.executeWithoutResult(
                        status -> journal.retire(operation.id(), operation.owner(), departed));
        }
    }

    private ClusterOperationJournal.Operation ownOrObserve(
            ClusterOperationJournal.Operation operation) {
        UUID id = operation.id();
        if (operation.owner().nodeId().equals(nodeId)
                && tx.execute(status -> journal.renew(id, operation.owner()))) return operation;
        var claimed = tx.execute(status -> journal.claim(id, nodeId, UUID.randomUUID()));
        if (claimed.isPresent()) {
            // Before commit, a lost coordinator's preparation is aborted. After commit, only apply.
            tx.executeWithoutResult(
                    status ->
                            journal.veto(
                                    id,
                                    nodeId,
                                    claimed.get().epoch(),
                                    "pluginOperationInterrupted"));
            return tx.execute(status -> journal.find(id).orElseThrow());
        }
        return tx.execute(status -> journal.find(id).orElseThrow());
    }

    private void advance(ClusterOperationJournal.Operation operation) {
        var phase = operation.phase();
        var next =
                switch (phase) {
                    case PREPARING -> ClusterOperationJournal.Phase.DRAINING;
                    case DRAINING -> ClusterOperationJournal.Phase.STAGING;
                    case STAGING -> ClusterOperationJournal.Phase.COMMITTED;
                    case COMMITTED -> ClusterOperationJournal.Phase.COMPLETED;
                    case ABORTING -> ClusterOperationJournal.Phase.ABORTED;
                    default -> throw new IllegalStateException("invalidActiveClusterPhase");
                };
        tx.executeWithoutResult(
                status -> {
                    if (!journal.advance(operation.id(), operation.owner(), phase, next)) return;
                    if (next == ClusterOperationJournal.Phase.COMMITTED) {
                        try {
                            participant.commit(operation);
                        } catch (Exception failure) {
                            throw new IllegalStateException("pluginClusterCommitFailed", failure);
                        }
                    }
                    if (next == ClusterOperationJournal.Phase.ABORTED) {
                        try {
                            participant.aborted(operation);
                        } catch (Exception failure) {
                            throw new IllegalStateException("pluginClusterAbortFailed", failure);
                        }
                    }
                });
    }

    private static void log(ClusterOperationJournal.Operation operation, Throwable failure) {
        System.getLogger(ClusterRuntimeCoordinator.class.getName())
                .log(
                        System.Logger.Level.WARNING,
                        "Cluster participant failed: " + operation.id() + " / " + operation.phase(),
                        failure);
    }
}
