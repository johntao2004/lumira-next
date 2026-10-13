package com.lumira.platform.monitoring;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.DurableEventProducerPort;
import com.lumira.platform.common.DurableEventTopologyPort;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.modules.ModuleCapabilityPort;
import com.sun.management.OperatingSystemMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonitoringService {
    public record MachineRuntime(
            String operatingSystem,
            String javaVersion,
            long uptimeMillis,
            int availableProcessors,
            Double systemCpuUsage,
            Double processCpuUsage,
            Long systemMemoryUsedBytes,
            Long systemMemoryTotalBytes,
            long heapUsedBytes,
            Long heapMaxBytes,
            int liveThreads,
            int daemonThreads) {}

    public record DatabaseRuntime(
            String status,
            Integer activeConnections,
            Integer idleConnections,
            Integer waitingConnections,
            Integer maxConnections) {}

    public record EventFlow(
            String status,
            int producerTypes,
            int consumerTypes,
            int connectedTypes,
            int unmatchedConsumerTypes,
            List<DurableEventProducerPort.EventType> missingConsumers,
            long createdLast5m,
            long deliveredLast5m,
            long retryPending,
            long overdue,
            List<MonitoringRepository.FlowEvent> recentEvents) {}

    public record Snapshot(
            MonitoringRepository.Summary summary,
            MachineRuntime machine,
            DatabaseRuntime database,
            EventFlow flow) {}

    private final MonitoringRepository repository;
    private final AdministrationPort guard;
    private final ModuleCapabilityPort modules;
    private final DataSource dataSource;
    private final DurableEventTopologyPort eventTopology;

    public MonitoringService(
            MonitoringRepository repository,
            AdministrationPort guard,
            ModuleCapabilityPort modules,
            DataSource dataSource,
            DurableEventTopologyPort eventTopology) {
        this.repository = repository;
        this.guard = guard;
        this.modules = modules;
        this.dataSource = dataSource;
        this.eventTopology = eventTopology;
    }

    @Transactional(readOnly = true)
    public Snapshot summary(String actor) {
        guard.requirePageGlobal(actor, PlatformPageId.MONITORING);
        var value = repository.summary(System.currentTimeMillis());
        var counters =
                new MonitoringRepository.Summary(
                        value.totalUsers(),
                        value.activeUsers(),
                        value.activeSessions(),
                        modules.enabledModules().size(),
                        modules.totalModules(),
                        value.pendingOutbox(),
                        value.failedOutbox(),
                        value.deadLetterOutbox(),
                        value.pendingMail(),
                        value.failedMail(),
                        value.pendingSms(),
                        value.failedSms(),
                        value.unreadNotifications(),
                        value.auditEvents24h(),
                        value.processingOutbox(),
                        value.eventsCreatedLast5m(),
                        value.eventsDeliveredLast5m(),
                        value.retryPendingOutbox(),
                        value.overdueOutbox(),
                        value.measuredAt());
        var topology = eventTopology.snapshot();
        var flowStatus = EventFlowStatus.resolve(topology, counters);
        return new Snapshot(
                counters,
                machineRuntime(),
                databaseRuntime(),
                new EventFlow(
                        flowStatus.name(),
                        topology.producerTypes(),
                        topology.consumerTypes(),
                        topology.connectedTypes(),
                        Math.max(0, topology.consumerTypes() - topology.connectedTypes()),
                        topology.missingConsumers(),
                        counters.eventsCreatedLast5m(),
                        counters.eventsDeliveredLast5m(),
                        counters.retryPendingOutbox(),
                        counters.overdueOutbox(),
                        repository.recentFlow()));
    }

    private MachineRuntime machineRuntime() {
        java.lang.management.OperatingSystemMXBean operatingSystem =
                ManagementFactory.getOperatingSystemMXBean();
        Double systemCpuUsage = null;
        Double processCpuUsage = null;
        Long systemMemoryTotalBytes = null;
        Long systemMemoryUsedBytes = null;
        if (operatingSystem instanceof OperatingSystemMXBean extended) {
            systemCpuUsage = normalizedLoad(extended.getCpuLoad());
            processCpuUsage = normalizedLoad(extended.getProcessCpuLoad());
            long total = extended.getTotalMemorySize();
            long free = extended.getFreeMemorySize();
            if (total >= 0 && free >= 0) {
                systemMemoryTotalBytes = total;
                systemMemoryUsedBytes = Math.max(0, total - free);
            }
        }
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long heapUsedBytes = memory.getHeapMemoryUsage().getUsed();
        long heapMaxBytes = memory.getHeapMemoryUsage().getMax();
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        return new MachineRuntime(
                operatingSystem.getName(),
                System.getProperty("java.version"),
                runtime.getUptime(),
                operatingSystem.getAvailableProcessors(),
                systemCpuUsage,
                processCpuUsage,
                systemMemoryUsedBytes,
                systemMemoryTotalBytes,
                Math.max(0, heapUsedBytes),
                heapMaxBytes >= 0 ? heapMaxBytes : null,
                threads.getThreadCount(),
                threads.getDaemonThreadCount());
    }

    private DatabaseRuntime databaseRuntime() {
        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            if (pool != null) {
                return new DatabaseRuntime(
                        "UP",
                        pool.getActiveConnections(),
                        pool.getIdleConnections(),
                        pool.getThreadsAwaitingConnection(),
                        hikari.getMaximumPoolSize());
            }
        }
        return new DatabaseRuntime("UP", null, null, null, null);
    }

    private static Double normalizedLoad(double load) {
        return Double.isFinite(load) && load >= 0 ? Math.min(load, 1d) : null;
    }
}
