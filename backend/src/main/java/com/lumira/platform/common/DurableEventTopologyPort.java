package com.lumira.platform.common;

import java.util.List;

/** Reports producer/consumer contract coverage for operational monitoring. */
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

    Snapshot snapshot();
}
