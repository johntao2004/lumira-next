package com.lumira.platform.common;

import java.util.List;

/**
 * 报告生产者与消费者契约覆盖情况，供运维监控。 Reports producer/consumer contract coverage for operational monitoring.
 */
public interface DurableEventTopologyPort {
    record Snapshot(
            int producerTypes,
            int consumerTypes,
            int connectedTypes,
            List<DurableEventProducerPort.EventType> missingConsumers) {
        public Snapshot {
            missingConsumers = List.copyOf(missingConsumers);
        }
    }

    /**
     * 返回当前生产与消费契约的拓扑快照，不触发消费。 Returns producer/consumer topology without triggering consumption.
     */
    Snapshot snapshot();
}
