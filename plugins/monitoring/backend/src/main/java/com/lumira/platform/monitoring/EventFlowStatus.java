package com.lumira.platform.monitoring;

import com.lumira.platform.common.DurableEventTopologyPort;

/** Operational classification for the durable event path. */
public final class EventFlowStatus {
    public enum Code {
        UNCONNECTED,
        IDLE,
        HEALTHY,
        PROCESSING,
        ATTENTION
    }

    private EventFlowStatus() {}

    public static Code resolve(
            DurableEventTopologyPort.Snapshot topology, MonitoringRepository.Summary counters) {
        if (topology == null
                || topology.producerTypes() == 0
                || topology.consumerTypes() == 0
                || topology.connectedTypes() < topology.producerTypes()
                || !topology.missingConsumers().isEmpty()) {
            return Code.UNCONNECTED;
        }
        // A registered consumer without a matching producer usually means the contract
        // catalog or consumer registration is stale. Keep it visible as an operational fault.
        if (topology.consumerTypes() > topology.connectedTypes()) {
            return Code.ATTENTION;
        }
        if (counters.failedOutbox() > 0
                || counters.deadLetterOutbox() > 0
                || counters.retryPendingOutbox() > 0
                || counters.overdueOutbox() > 0) {
            return Code.ATTENTION;
        }
        if (counters.pendingOutbox() > 0 || counters.processingOutbox() > 0) {
            return Code.PROCESSING;
        }
        if (counters.eventsDeliveredLast5m() > 0) {
            return Code.HEALTHY;
        }
        if (counters.eventsCreatedLast5m() > 0) {
            return Code.PROCESSING;
        }
        return Code.IDLE;
    }
}
