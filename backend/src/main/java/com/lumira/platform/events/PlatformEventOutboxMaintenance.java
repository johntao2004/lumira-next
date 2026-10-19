/**
 * 定期恢复过期消费租约并按保留期分批清理已投递事件，使中断任务可重试且历史数据增长有界。 Periodically recovers expired consumption leases and
 * purges delivered events in retention-limited batches so interrupted work can retry without
 * unbounded historical growth.
 */
package com.lumira.platform.events;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 即使未安装可选事件消费者，也保持遗弃领取可恢复。 Keeps abandoned claims recoverable even while no optional event consumer
 * is installed.
 */
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
