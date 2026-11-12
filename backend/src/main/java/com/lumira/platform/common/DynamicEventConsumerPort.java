package com.lumira.platform.common;

import java.util.Collection;
import java.util.function.Supplier;

/** Atomic, owned registration. The lease covers consumption and transaction completion. */
public interface DynamicEventConsumerPort {
    AutoCloseable registerProducers(String owner, Collection<DurableEventProducerPort> producers);

    AutoCloseable registerConsumers(
            String owner,
            Collection<DurableEventConsumerPort> consumers,
            Supplier<AutoCloseable> admission);
}
