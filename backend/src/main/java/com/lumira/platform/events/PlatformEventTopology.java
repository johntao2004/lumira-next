package com.lumira.platform.events;

import com.lumira.platform.common.DurableEventProducerPort;
import com.lumira.platform.common.DurableEventTopologyPort;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Computes actual producer/consumer contract coverage for monitoring. */
@Component
public final class PlatformEventTopology implements DurableEventTopologyPort {
    private static final Comparator<DurableEventProducerPort.EventType> EVENT_ORDER =
            Comparator.comparing(DurableEventProducerPort.EventType::sourceType)
                    .thenComparing(DurableEventProducerPort.EventType::eventType);

    private final List<DurableEventProducerPort> producers;
    private final PlatformEventConsumerRegistry consumers;

    public PlatformEventTopology(
            List<DurableEventProducerPort> producers, PlatformEventConsumerRegistry consumers) {
        this.producers = producers == null ? List.of() : List.copyOf(producers);
        this.consumers = consumers;
    }

    @Override
    public Snapshot snapshot() {
        Set<DurableEventProducerPort.EventType> produced = new LinkedHashSet<>();
        produced.addAll(consumers.producedEventTypes());
        for (var producer : producers) {
            var declared = producer.eventTypes();
            if (declared != null) produced.addAll(declared);
        }
        var registeredConsumers = consumers.eventTypes();
        long connected = produced.stream().filter(registeredConsumers::contains).count();
        var missing =
                produced.stream()
                        .filter(type -> !registeredConsumers.contains(type))
                        .sorted(EVENT_ORDER)
                        .toList();
        return new Snapshot(
                produced.size(), registeredConsumers.size(), Math.toIntExact(connected), missing);
    }
}
