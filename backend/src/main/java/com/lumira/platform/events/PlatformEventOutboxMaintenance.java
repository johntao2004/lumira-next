package com.lumira.platform.events;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps abandoned claims recoverable even while no optional event consumer is installed. */
@Component
public class PlatformEventOutboxMaintenance {
    private final PlatformEventOutboxStore store;
    private final Duration retention;

    public PlatformEventOutboxMaintenance(
            PlatformEventOutboxStore store,
            @Value("${lumira.events.delivered-retention:30d}") Duration retention) {
        this.store = store;
        this.retention = retention;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 15000)
    public void recoverExpiredClaims() {
        store.recoverExpired();
    }

    @Scheduled(
            fixedDelayString = "${lumira.events.retention-interval-ms:86400000}",
            initialDelayString = "${lumira.events.retention-initial-delay-ms:86400000}")
    public void purgeDelivered() {
        store.purgeDelivered(retention, 1000);
    }
}
