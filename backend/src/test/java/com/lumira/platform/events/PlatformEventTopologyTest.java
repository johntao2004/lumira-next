package com.lumira.platform.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lumira.platform.common.DurableEventConsumerPort;
import com.lumira.platform.common.DurableEventProducerPort;
import com.lumira.platform.common.DurableEventTopologyPort;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformEventTopologyTest {
    @Test
    void reportsOnlyProducerContractsWithoutAnExactConsumerMatch() {
        var connected = new DurableEventProducerPort.EventType("feedback", "feedback.created");
        var missing = new DurableEventProducerPort.EventType("broadcast", "broadcast.created");
        var producer = (DurableEventProducerPort) () -> List.of(connected, missing, connected);
        var consumer = consumer("feedback", "feedback.created");
        var unrelatedConsumer = consumer("other", "other.created");
        var registry =
                new PlatformEventConsumerRegistry(List.of(consumer, unrelatedConsumer), List.of());

        DurableEventTopologyPort topology = new PlatformEventTopology(List.of(producer), registry);

        assertEquals(
                new DurableEventTopologyPort.Snapshot(2, 2, 1, List.of(missing)),
                topology.snapshot());
    }

    private static DurableEventConsumerPort consumer(String source, String event) {
        return new DurableEventConsumerPort() {
            @Override
            public String sourceType() {
                return source;
            }

            @Override
            public String eventType() {
                return event;
            }

            @Override
            public void consume(Event ignored) {}
        };
    }
}
