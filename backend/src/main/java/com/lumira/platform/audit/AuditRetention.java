package com.lumira.platform.audit;

import com.lumira.platform.modules.ModuleRuntime;
import java.time.*;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded scheduled retention for the append-only administrative audit stream. */
@Component
public final class AuditRetention {
    private static final Duration MIN_RETENTION = Duration.ofDays(7);
    private static final Duration MAX_RETENTION = Duration.ofDays(3650);
    private final AuditRepository repository;
    private final Duration retention;
    private final int batchSize;
    private final Clock clock;
    private final ModuleRuntime modules;

    @Autowired
    public AuditRetention(
            AuditRepository repository,
            @Value("${lumira.audit.retention:365d}") Duration retention,
            @Value("${lumira.audit.retention-batch:1000}") int batchSize,
            ModuleRuntime modules) {
        this(repository, retention, batchSize, Clock.systemUTC(), modules);
    }

    AuditRetention(AuditRepository repository, Duration retention, int batchSize, Clock clock) {
        this(repository, retention, batchSize, clock, moduleId -> true);
    }

    AuditRetention(
            AuditRepository repository,
            Duration retention,
            int batchSize,
            Clock clock,
            ModuleRuntime modules) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.retention = validateRetention(retention);
        if (batchSize < 1 || batchSize > 5000)
            throw new IllegalArgumentException("Audit retention batch must be 1..5000");
        this.batchSize = batchSize;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.modules = Objects.requireNonNull(modules, "modules");
    }

    static Duration validateRetention(Duration value) {
        if (value == null
                || value.isNegative()
                || value.compareTo(MIN_RETENTION) < 0
                || value.compareTo(MAX_RETENTION) > 0)
            throw new IllegalArgumentException("Audit retention must be between 7d and 10y");
        return value;
    }

    @Scheduled(
            fixedDelayString = "${lumira.audit.retention-interval-ms:86400000}",
            initialDelayString = "${lumira.audit.retention-initial-delay-ms:86400000}")
    public void purge() {
        if (!modules.isEnabled("audit-console")) return;
        repository.purgeBefore(OffsetDateTime.now(clock).minus(retention), batchSize);
    }
}
