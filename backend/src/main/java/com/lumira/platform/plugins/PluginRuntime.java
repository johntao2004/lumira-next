package com.lumira.platform.plugins;

import com.lumira.platform.common.*;
import com.lumira.platform.modules.*;
import com.lumira.plugin.sdk.PluginEntry;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.pf4j.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.ObjectMapper;

/** Every published extension has an owned, drainable context and database transaction fences. */
@Service
public class PluginRuntime
        implements AutoCloseable,
                PluginAuthorizationPort,
                PluginExtensionsPort,
                PluginServicesPort,
                ModuleStateChangeGuard,
                HealthIndicator {
    public record Installation(
            String moduleId, String digest, boolean installed, boolean explicitInstall) {}

    public record View(
            String id,
            String name,
            String version,
            String digest,
            boolean installed,
            boolean enabled,
            boolean explicitInstall,
            String status,
            PluginManifest manifest,
            boolean lifecycleChangesAllowed,
            String lifecycleBlockReason) {}

    public record Plan(
            String moduleId,
            String kind,
            String revision,
            List<String> install,
            List<String> reuse,
            List<String> enable,
            List<String> remove,
            List<String> blockers,
            String dataMode,
            boolean dataDeletionSupported,
            List<PluginDataRemoval.Count> data) {}

    public record Execute(String revision, String dataMode, String confirmation) {}

    public record ApprovedPlugin(String id, String version, String digest) {}

    public record ApprovedGraph(int formatVersion, List<ApprovedPlugin> plugins) {
        public ApprovedGraph {
            plugins = List.copyOf(plugins == null ? List.of() : plugins);
        }
    }

    public record Operation(
            UUID id,
            String moduleId,
            String kind,
            String status,
            String step,
            String errorCode,
            String dataMode) {}

    private record Published(
            RequestMappingInfo mapping, Object bean, java.lang.reflect.Method method) {}

    private record RuntimeNode(UUID nodeId, boolean ready, String graphRevision) {}

    private record RuntimeRevisionSnapshot(
            String artifacts, String installations, String moduleStates) {}

    private record RuntimeGraphSnapshot(
            String revision, String approvedRevision, long nodeLeaseDeadline) {}

    private record TrafficReadiness(boolean ready, long nodeLeaseDeadline) {}

    private record TrafficTransition(
            Set<String> affected, String unchangedRevision, Set<String> approvals) {}

    private record TrafficCheck(
            String revision, TrafficTransition transition, boolean ready, long nodeLeaseDeadline) {}

    /** One servlet dispatch only: no cross-request or background-worker readiness cache. */
    final class TrafficScope implements AutoCloseable {
        private final TrafficScope previous;
        private final ModuleStateStore.Scope moduleScope;
        private TrafficCheck check;
        private boolean closed;

        private TrafficScope(TrafficScope previous) {
            this.previous = previous;
            this.moduleScope = ModuleStateStore.openScope(null);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            moduleScope.close();
            if (previous == null) trafficScope.remove();
            else {
                // A nested dispatch can observe a newer graph; do not restore an older result.
                previous.check = null;
                trafficScope.set(previous);
            }
        }
    }

    private static final class Handle {
        final String owner;
        final AnnotationConfigApplicationContext context;
        final List<Published> mappings;
        final PluginOwnedResources resources;
        String digest = "";
        volatile long generation;
        boolean accepting = true;
        int calls;

        Handle(
                String owner,
                AnnotationConfigApplicationContext context,
                List<Published> mappings,
                PluginOwnedResources resources) {
            this.owner = owner;
            this.context = context;
            this.mappings = mappings;
            this.resources = resources;
        }
    }

    private static final long CLUSTER_LOCK_KEY = 481028L;
    private static final Duration NODE_STALE_AFTER = Duration.ofSeconds(30);
    private static final Duration OPERATION_LEASE = Duration.ofSeconds(45);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final ModuleRegistry registry;
    private final ModuleStateStore states;
    private final ObjectProvider<PluginDependenciesPort> configuredDependencies;
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final ConfigurableApplicationContext host;
    private final ObjectProvider<RequestMappingHandlerMapping> mappingProvider;
    private final TransactionTemplate tx;
    private final Path root;
    private final PluginArtifactStore artifactStore;
    private final DataSource dataSource;
    private final ClusterOperationJournal clusterJournal;
    private final ClusterRuntimeCoordinator clusterCoordinator;

    @Value("${lumira.plugins.cluster-enabled:false}")
    private boolean clusterEnabled;

    private volatile boolean clusterFenced;
    private boolean clusterColdRecovery;
    private final ScheduledExecutorService clusterExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("lumira-plugin-cluster").factory());
    private final Path approvedGraphPath;
    private final boolean fixedGraph;
    private final UUID nodeId = UUID.randomUUID();
    private final PluginManager manager;
    private final Map<String, PluginArchive.Artifact> artifacts = new ConcurrentHashMap<>();

    private record IndexedArtifact(PluginArchive.Artifact artifact, PluginHttpRuleIndex rules) {}

    private final Map<String, IndexedArtifact> routeIndexes = new ConcurrentHashMap<>();
    private final Map<String, Handle> handles = new ConcurrentHashMap<>();
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().name("lumira-plugin-lifecycle").factory());
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("lumira-plugin-heartbeat").factory());
    private final Object lock = new Object();
    private volatile boolean busy;
    private volatile TrafficTransition trafficTransition;
    private volatile boolean ready;
    private volatile boolean heartbeatHealthy;
    private volatile boolean nodeRegistered;
    private volatile String loadedGraphRevision = "";
    private final ThreadLocal<TrafficScope> trafficScope = new ThreadLocal<>();

    private record CachedSnapshot(
            Set<String> excluded,
            String sig,
            String revision,
            String artifacts,
            String installations,
            String moduleStates,
            Map<String, Boolean> moduleEnabled) {}

    private final ConcurrentHashMap<Set<String>, CachedSnapshot> snapshotCache =
            new ConcurrentHashMap<>();

    public PluginRuntime(
            JdbcClient jdbc,
            ObjectMapper mapper,
            ModuleRegistry registry,
            ModuleStateStore states,
            AdministrationPort guard,
            AuditPort audit,
            ConfigurableApplicationContext host,
            @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
                    ObjectProvider<RequestMappingHandlerMapping> mappingProvider,
            PlatformTransactionManager transactions,
            @Value("${lumira.plugins.path:.runtime/plugins}") String path,
            @Value("${lumira.plugins.artifacts-path:}") String artifactsPath,
            DataSource dataSource,
            @Value("${lumira.plugins.fixed-graph:false}") boolean fixedGraph,
            @Value("${lumira.plugins.approved-graph:plugins/approved-graph.json}")
                    String approvedGraph,
            ObjectProvider<PluginDependenciesPort> configuredDependencies) {
        this.jdbc = jdbc;
        clusterJournal = new ClusterOperationJournal(dataSource, mapper);
        this.mapper = mapper;
        this.registry = registry;
        this.states = states;
        this.configuredDependencies = Objects.requireNonNull(configuredDependencies);
        this.guard = guard;
        this.audit = audit;
        this.host = host;
        this.mappingProvider = mappingProvider;
        tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        clusterCoordinator =
                new ClusterRuntimeCoordinator(clusterJournal, tx, nodeId, new ClusterParticipant());
        root = Path.of(path).toAbsolutePath().normalize();
        artifactStore = new PluginArtifactStore(root, artifactsPath);
        this.dataSource = dataSource;
        this.fixedGraph = fixedGraph;
        approvedGraphPath = Path.of(approvedGraph).toAbsolutePath().normalize();
        manager =
                new DefaultPluginManager(root.resolve("loaded")) {
                    @Override
                    protected PluginLoader createPluginLoader() {
                        return new JarPluginLoader(this) {
                            @Override
                            protected PluginClassLoader createPluginClassLoader(
                                    Path p, PluginDescriptor d) {
                                var loader =
                                        new PluginClassLoader(
                                                pluginManager,
                                                d,
                                                PluginRuntime.class.getClassLoader(),
                                                ClassLoadingStrategy.PDA) {
                                            @Override
                                            public Class<?> loadClass(String name)
                                                    throws ClassNotFoundException {
                                                if (name.startsWith("org.springframework.")
                                                        || name.startsWith("com.lumira.plugin.sdk.")
                                                        || name.startsWith(
                                                                "com.lumira.platform.common.")
                                                        || name.startsWith(
                                                                "com.lumira.platform.modules.")
                                                        || name.startsWith("tools.jackson.")
                                                        || name.startsWith("jakarta."))
                                                    return PluginRuntime.class
                                                            .getClassLoader()
                                                            .loadClass(name);
                                                // Host contracts win; a plugin may also own
                                                // response DTOs in its own api package.
                                                if (name.startsWith("com.lumira.platform.")
                                                        && name.contains(".api.")) {
                                                    try {
                                                        return PluginRuntime.class
                                                                .getClassLoader()
                                                                .loadClass(name);
                                                    } catch (
                                                            ClassNotFoundException pluginOwnedApi) {
                                                        return super.loadClass(name);
                                                    }
                                                }
                                                return super.loadClass(name);
                                            }
                                        };
                                var artifact = artifacts.get(d.getPluginId());
                                if (artifact != null)
                                    for (Path library : PluginArchive.libraries(artifact))
                                        loader.addFile(library.toFile());
                                return loader;
                            }
                        };
                    }
                };
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private void requireLifecyclePermission(String actor, String kind) {
        guard.requireGlobal(actor, PlatformPageId.MODULES.accessPermission());
        String permission =
                switch (kind) {
                    case "INSTALL" -> PlatformOperation.MODULE_INSTALL.permission();
                    case "UNINSTALL" -> PlatformOperation.MODULE_UNINSTALL.permission();
                    default ->
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST, "invalidPluginOperation");
                };
        guard.requireGlobal(actor, permission);
    }

    private void requireLifecyclePermissions(String actor, Plan plan) {
        requireLifecyclePermission(actor, plan.kind());
        if (!plan.enable().isEmpty())
            guard.requireGlobal(actor, PlatformOperation.MODULE_UPDATE.permission());
    }

    private void read(String actor) {
        guard.requireGlobal(actor, PlatformPageId.MODULES.accessPermission());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore() throws Exception {
        synchronized (lock) {
            registerNode();
            startHeartbeat();
            try {
                boolean restoredNode = false;
                while (!restoredNode) {
                    if (clusterEnabled
                            && (clusterJournal.blocksGraphChange()
                                    || clusterCoordinator.hasPendingTerminal())) {
                        clusterCoordinator.tick();
                        if (clusterFenced)
                            throw new IllegalStateException("pluginRuntimeNodeRetired");
                        if (clusterJournal.blocksGraphChange()
                                && !clusterCoordinator.hasPendingTerminal())
                            TimeUnit.MILLISECONDS.sleep(200);
                        continue;
                    }
                    restoredNode =
                            withClusterLifecycleLock(
                                    () -> {
                                        if (clusterEnabled
                                                && (clusterJournal.blocksGraphChange()
                                                        || clusterCoordinator.hasPendingTerminal()))
                                            return false;
                                        String restored;
                                        if (fixedGraph) {
                                            restored =
                                                    tx.execute(
                                                            status -> {
                                                                try {
                                                                    return restoreUnderClusterLock();
                                                                } catch (RuntimeException
                                                                        | Error failure) {
                                                                    throw failure;
                                                                } catch (Exception failure) {
                                                                    throw new IllegalStateException(
                                                                            "pluginRestoreFailed",
                                                                            failure);
                                                                }
                                                            });
                                        } else restored = restoreUnderClusterLock();
                                        // Traffic is published only after the actual metadata
                                        // transaction
                                        // commits.
                                        loadedGraphRevision = Objects.requireNonNull(restored);
                                        ready = true;
                                        updateNodeState(true, loadedGraphRevision);
                                        return true;
                                    });
                }
                if (clusterEnabled)
                    clusterExecutor.scheduleWithFixedDelay(
                            () -> {
                                try {
                                    clusterCoordinator.tick();
                                } catch (RuntimeException failure) {
                                    System.getLogger(getClass().getName())
                                            .log(
                                                    System.Logger.Level.WARNING,
                                                    "Plugin cluster control-plane tick failed",
                                                    failure);
                                }
                            },
                            0,
                            250,
                            TimeUnit.MILLISECONDS);
            } catch (Exception | Error failure) {
                ready = false;
                updateNodeState(false, "");
                throw failure;
            }
        }
    }

    private String restoreUnderClusterLock() throws Exception {
        clusterColdRecovery = false;
        trafficTransition = null;
        busy = false;
        artifactStore.initialize();
        recoverExpiredOperations();
        boolean bootstrap =
                jdbc.sql("SELECT count(*) FROM platform_plugin_bootstrap")
                                .query(Integer.class)
                                .single()
                        == 0;
        List<String> bootstrapDefaults =
                bootstrap && !fixedGraph
                        ? bootstrapPluginIds(Path.of("plugins", "bootstrap.json"), mapper)
                        : List.of();
        Map<String, ApprovedPlugin> approved =
                fixedGraph ? readApprovedPackages(bootstrap) : Map.of();
        Set<String> removedProviders =
                fixedGraph ? reconcileApprovedInstallations(approved, bootstrap) : Set.of();
        for (var row :
                jdbc.sql(
                                "SELECT digest,manifest::text FROM platform_plugin_artifact ORDER BY created_at")
                        .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2)))
                        .list()) {
            if (fixedGraph
                    && approved.values().stream().noneMatch(p -> p.digest().equals(row.getKey())))
                continue;
            var manifest = mapper.readValue(row.getValue(), PluginManifest.class);
            var artifact = artifactStore.stored(row.getKey(), manifest);
            artifacts.put(manifest.id(), artifact);
        }
        if (bootstrap && !fixedGraph) {
            for (String id : bootstrapDefaults) {
                Path packageDirectory = approvedGraphPath.getParent();
                Path manifestPath =
                        fixedGraph
                                ? packageDirectory.resolve(id + "/plugin.json")
                                : Path.of("plugins", id, "plugin.json");
                String version =
                        mapper.readTree(Files.readString(manifestPath)).path("version").asText();
                Path p =
                        fixedGraph
                                ? approvedArchivePath(packageDirectory, id, version)
                                : Path.of(
                                        "plugins",
                                        id,
                                        "dist",
                                        id + "-" + version + ".lumira-plugin.zip");
                if (!Files.isRegularFile(p))
                    throw new IllegalStateException(
                            "Build plugin packages first: pnpm plugins:build (missing " + p + ")");
                importArchive(Files.readAllBytes(p), 0);
            }
        }
        // The installation digest, rather than catalog import time, selects the running
        // version.
        for (var installation : installationMap().values())
            if (installation.installed()) {
                var persisted =
                        jdbc.sql(
                                        "SELECT manifest::text FROM platform_plugin_artifact WHERE digest=:digest")
                                .param("digest", installation.digest())
                                .query(String.class)
                                .optional()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "Missing installed artifact: "
                                                                + installation.moduleId()));
                var manifest = mapper.readValue(persisted, PluginManifest.class);
                artifacts.put(manifest.id(), artifactStore.stored(installation.digest(), manifest));
            }
        registerCatalog();
        if (bootstrap && !fixedGraph) {
            Set<String> initialInstallations = bootstrapClosure(bootstrapDefaults);
            tx.executeWithoutResult(
                    status -> {
                        fenceLifecycle(initialInstallations);
                        for (String id : initialInstallations) {
                            var a = artifacts.get(id);
                            jdbc.sql(
                                            "INSERT INTO platform_plugin_installation(module_id,digest,installed,explicit_install) VALUES(:id,:digest,true,:explicit) ON CONFLICT(module_id) DO NOTHING")
                                    .param("id", a.manifest().id())
                                    .param("digest", a.digest())
                                    .param("explicit", bootstrapDefaults.contains(id))
                                    .update();
                            jdbc.sql(
                                            "INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(:uuid,:id,true,1) ON CONFLICT(module_id) DO NOTHING")
                                    .param("uuid", UUID.randomUUID())
                                    .param("id", a.manifest().id())
                                    .update();
                        }
                        jdbc.sql("INSERT INTO platform_plugin_bootstrap(id) VALUES(1)").update();
                    });
            registerCatalog();
        }
        if (fixedGraph) validateApprovedInstallations(approved);
        for (String id : orderAll())
            if (installed(id) && storedEnabled(id)) {
                try {
                    load(id);
                } catch (Exception | LinkageError error) {
                    registry.live(id, false);
                    throw new IllegalStateException("Plugin restore failed: " + id, error);
                }
            }
        // Flyway owns independent plugin initialization history. Do not hold the recovery
        // barrier across its connections; take it only for final reference validation/publication.
        if (fixedGraph) verifyFixedReleaseCanPublish(removedProviders);
        String currentRevision = revision();
        requireApprovedGraph(currentRevision);
        return currentRevision;
    }

    private void registerNode() {
        nodeProtocolWrite(
                () ->
                        jdbc.sql(
                                        "INSERT INTO platform_plugin_runtime_node(node_id,started_at,heartbeat_at,ready,fixed_graph,graph_revision) VALUES(:id,now(),now(),false,:fixed,'')")
                                .param("id", nodeId)
                                .param("fixed", fixedGraph)
                                .update());
        nodeRegistered = true;
        heartbeatHealthy = true;
    }

    private void startHeartbeat() {
        heartbeatExecutor.scheduleWithFixedDelay(
                this::heartbeatTick,
                HEARTBEAT_INTERVAL.toSeconds(),
                HEARTBEAT_INTERVAL.toSeconds(),
                TimeUnit.SECONDS);
    }

    private void heartbeatTick() {
        if (clusterFenced) return;
        try {
            recoverExpiredOperations();
            String currentRevision = revision();
            boolean graphReady = isReadyForTraffic();
            int updated =
                    nodeProtocolWrite(
                            () ->
                                    jdbc.sql(
                                                    "UPDATE platform_plugin_runtime_node SET heartbeat_at=now(),ready=:ready,graph_revision=:revision WHERE node_id=:id")
                                            .param("ready", graphReady)
                                            .param("revision", loadedGraphRevision)
                                            .param("id", nodeId)
                                            .update());
            if (updated != 1) throw new IllegalStateException("pluginRuntimeNodeUnavailable");
            jdbc.sql(
                            "UPDATE platform_plugin_operation SET lease_expires_at=clock_timestamp()+interval '45 seconds',updated_at=clock_timestamp() WHERE owner_node_id=:id AND status IN ('QUEUED','RUNNING') AND lease_expires_at>clock_timestamp()")
                    .param("id", nodeId)
                    .update();
            jdbc.sql(
                            "DELETE FROM platform_plugin_runtime_node WHERE node_id<>:id AND heartbeat_at<now()-interval '5 minutes'")
                    .param("id", nodeId)
                    .update();
            heartbeatHealthy = true;
            if (clusterEnabled) clusterCoordinator.renewLease();
        } catch (RuntimeException failure) {
            heartbeatHealthy = false;
            System.getLogger(getClass().getName())
                    .log(System.Logger.Level.WARNING, "Plugin runtime heartbeat failed", failure);
        }
    }

    private void recoverExpiredOperations() {
        jdbc.sql(
                        "UPDATE platform_plugin_operation SET status='FAILED',error_code='pluginOperationInterrupted',step=CASE WHEN owner_node_id IS NULL THEN 'legacy operation had no runtime owner lease' ELSE 'owner lease expired' END,lease_expires_at=NULL,updated_at=now() WHERE status IN ('QUEUED','RUNNING') AND ((owner_node_id IS NOT NULL AND lease_expires_at IS NOT NULL AND lease_expires_at<now()) OR (owner_node_id IS NULL AND updated_at<now()-interval '5 minutes'))")
                .update();
    }

    private void updateNodeState(boolean nodeReady, String graphRevision) {
        if (!nodeRegistered) return;
        nodeProtocolWrite(
                () ->
                        jdbc.sql(
                                        "UPDATE platform_plugin_runtime_node SET heartbeat_at=now(),ready=:ready,graph_revision=:revision WHERE node_id=:id")
                                .param("ready", nodeReady)
                                .param("revision", graphRevision)
                                .param("id", nodeId)
                                .update());
    }

    private int nodeProtocolWrite(java.util.function.IntSupplier write) {
        if (!clusterEnabled) return write.getAsInt();
        return tx.execute(
                status -> {
                    jdbc.sql("SELECT set_config('lumira.cluster_protocol','2',true)")
                            .query(String.class)
                            .single();
                    return write.getAsInt();
                });
    }

    private boolean clusterEligible() {
        return clusterEnabled
                && !fixedGraph
                && clusterJournal.protocolReady()
                && handles.containsKey("cluster-runtime")
                && isModuleEnabled("cluster-runtime");
    }

    private List<RuntimeNode> activeNodes() {
        return jdbc.sql(
                        "SELECT node_id,ready,graph_revision FROM platform_plugin_runtime_node WHERE heartbeat_at>now()-interval '30 seconds' ORDER BY node_id")
                .query(RuntimeNode.class)
                .list();
    }

    private int activeNodeCount() {
        return jdbc.sql(
                        "SELECT count(*) FROM platform_plugin_runtime_node WHERE heartbeat_at>now()-interval '30 seconds'")
                .query(Integer.class)
                .single();
    }

    private Map<String, ApprovedPlugin> readApprovedPackages(boolean importIntoCatalog)
            throws Exception {
        ApprovedGraph graph =
                mapper.readValue(Files.readString(approvedGraphPath), ApprovedGraph.class);
        if (graph.formatVersion() != 1 || graph.plugins().isEmpty())
            throw new IllegalStateException("invalidApprovedPluginGraph");
        Path packageDirectory = approvedGraphPath.getParent();
        Map<String, ApprovedPlugin> approved = new TreeMap<>();
        for (ApprovedPlugin plugin : graph.plugins()) {
            if (plugin == null
                    || plugin.id() == null
                    || !plugin.id().matches("[a-z][a-z0-9-]{0,63}")
                    || plugin.version() == null
                    || !plugin.version().matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                    || plugin.digest() == null
                    || !plugin.digest().matches("[a-f0-9]{64}")
                    || approved.putIfAbsent(plugin.id(), plugin) != null)
                throw new IllegalStateException("invalidApprovedPluginGraph");
            Path manifestPath = packageDirectory.resolve(plugin.id()).resolve("plugin.json");
            PluginManifest descriptor =
                    mapper.readValue(Files.readString(manifestPath), PluginManifest.class);
            if (!plugin.id().equals(descriptor.id())
                    || !plugin.version().equals(descriptor.version()))
                throw new IllegalStateException("approvedPluginDescriptorMismatch");
            Path archive = approvedArchivePath(packageDirectory, plugin.id(), plugin.version());
            byte[] bytes = Files.readAllBytes(archive);
            if (!plugin.digest().equals(PluginArchive.digest(bytes)))
                throw new IllegalStateException("approvedPluginDigestMismatch");
            if (importIntoCatalog) importArchive(bytes, 0);
        }
        return Map.copyOf(approved);
    }

    private Set<String> reconcileApprovedInstallations(
            Map<String, ApprovedPlugin> approved, boolean bootstrap) {
        Set<String> bootstrapSelection =
                bootstrap ? approvedBootstrapSelection(approved) : Set.of();
        Set<String> bootstrapInstallations =
                bootstrap ? bootstrapClosure(bootstrapSelection) : Set.of();
        for (String id : bootstrapInstallations)
            if (!approved.containsKey(id))
                throw new IllegalArgumentException("pluginBootstrapNotApproved:" + id);
        boolean unchanged = !bootstrap && approvedDatabaseMatches(approved);
        if (!bootstrap && !unchanged) {
            long otherLiveNodes =
                    jdbc.sql(
                                    "SELECT count(*) FROM platform_plugin_runtime_node WHERE node_id<>:id AND heartbeat_at>now()-interval '30 seconds' AND (ready OR graph_revision<>'')")
                            .param("id", nodeId)
                            .query(Long.class)
                            .single();
            if (otherLiveNodes > 0) throw conflict("pluginReleaseNodesActive");
        }
        String priorApproval =
                jdbc.sql("SELECT graph_revision FROM platform_plugin_graph_approval WHERE id=1")
                        .query(String.class)
                        .optional()
                        .orElse(null);
        if (priorApproval != null && !priorApproval.equals(revision()))
            throw conflict("pluginGraphApprovalMismatch");
        // HPA/cold replacement of the same approved graph must neither rewrite metadata nor
        // acquire exclusive fences that would conflict with healthy nodes' business transactions.
        if (unchanged) return Set.of();
        Set<String> removedProviders =
                bootstrap ? Set.of() : requireApprovedRemovalAllowed(approved);
        var installations = installationMap();
        var enabledFlags = new HashMap<String, Boolean>();
        jdbc.sql("SELECT module_id,enabled FROM platform_module_state")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getBoolean(2)))
                .list()
                .forEach(entry -> enabledFlags.put(entry.getKey(), entry.getValue()));
        var affected = new HashSet<String>();
        for (var plugin : approved.values()) {
            var current = installations.get(plugin.id());
            if (bootstrap
                    || current == null
                    || !plugin.digest().equals(current.digest())
                    || !enabledFlags.containsKey(plugin.id())
                    || (!current.installed() && enabledFlags.get(plugin.id())))
                affected.add(plugin.id());
        }
        installations.values().stream()
                .filter(i -> !approved.containsKey(i.moduleId()))
                .filter(
                        i ->
                                i.installed()
                                        || i.explicitInstall()
                                        || enabledFlags.getOrDefault(i.moduleId(), false))
                .forEach(i -> affected.add(i.moduleId()));
        tx.executeWithoutResult(
                status -> {
                    fenceLifecycle(affected);
                    if (!bootstrap) requireApprovedRemovalAllowed(approved);
                    Set<String> included = new HashSet<>();
                    for (ApprovedPlugin plugin : approved.values()) {
                        included.add(plugin.id());
                        if (!affected.contains(plugin.id())) continue;
                        PluginArchive.Artifact artifact = artifacts.get(plugin.id());
                        if (artifact == null || !plugin.digest().equals(artifact.digest())) {
                            try {
                                byte[] bytes =
                                        Files.readAllBytes(
                                                approvedArchivePath(
                                                        approvedGraphPath.getParent(),
                                                        plugin.id(),
                                                        plugin.version()));
                                importArchive(bytes, 0);
                            } catch (Exception e) {
                                throw new IllegalStateException("approvedPluginImportFailed", e);
                            }
                        }
                        Installation current = installations.get(plugin.id());
                        boolean installed =
                                bootstrap
                                        ? bootstrapInstallations.contains(plugin.id())
                                        : current != null && current.installed();
                        boolean explicit =
                                bootstrap
                                        ? bootstrapSelection.contains(plugin.id())
                                        : current != null && current.explicitInstall();
                        jdbc.sql(
                                        "INSERT INTO platform_plugin_installation(module_id,digest,installed,explicit_install) VALUES(:id,:digest,:installed,:explicit) ON CONFLICT(module_id) DO UPDATE SET digest=EXCLUDED.digest,installed=EXCLUDED.installed,explicit_install=EXCLUDED.explicit_install,updated_at=now()")
                                .param("id", plugin.id())
                                .param("digest", plugin.digest())
                                .param("installed", installed)
                                .param("explicit", explicit)
                                .update();
                        boolean previousEnabled = enabledFlags.getOrDefault(plugin.id(), false);
                        boolean enabled =
                                bootstrap
                                        ? bootstrapInstallations.contains(plugin.id())
                                        : installed && previousEnabled;
                        jdbc.sql(
                                        "INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(:uuid,:id,:enabled,1) ON CONFLICT(module_id) DO UPDATE SET enabled=EXCLUDED.enabled,version=platform_module_state.version+1,updated_at=now() WHERE platform_module_state.enabled IS DISTINCT FROM EXCLUDED.enabled")
                                .param("uuid", UUID.randomUUID())
                                .param("id", plugin.id())
                                .param("enabled", enabled)
                                .update();
                    }
                    var removed = new HashSet<>(affected);
                    removed.removeAll(included);
                    if (!removed.isEmpty()) {
                        jdbc.sql(
                                        "UPDATE platform_plugin_installation SET installed=false,explicit_install=false,updated_at=now() WHERE module_id IN (:ids) AND (installed OR explicit_install)")
                                .param("ids", removed)
                                .update();
                        jdbc.sql(
                                        "UPDATE platform_module_state SET enabled=false,version=version+1,updated_at=now() WHERE module_id IN (:ids) AND enabled")
                                .param("ids", removed)
                                .update();
                    }
                    if (bootstrap) {
                        jdbc.sql(
                                        "INSERT INTO platform_plugin_bootstrap(id) VALUES(1) ON CONFLICT DO NOTHING")
                                .update();
                    }
                });
        return removedProviders;
    }

    private Set<String> requireApprovedRemovalAllowed(Map<String, ApprovedPlugin> approved) {
        var removed = new TreeSet<String>();
        installationMap().values().stream()
                .filter(i -> i.installed() && !approved.containsKey(i.moduleId()))
                .forEach(i -> removed.add(i.moduleId()));
        if (removed.isEmpty()) return Set.of();
        // Use the committed old graph, including dependents also omitted by the new image.
        // Omission cannot turn a dependent chain into an implicit cascading uninstall.
        for (var row :
                jdbc.sql(
                                "SELECT i.module_id,a.manifest::text FROM platform_plugin_installation i JOIN platform_plugin_artifact a ON a.digest=i.digest WHERE i.installed ORDER BY i.module_id")
                        .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2)))
                        .list()) {
            var manifest = mapper.readValue(row.getValue(), PluginManifest.class);
            if (!row.getKey().equals(manifest.id()))
                throw new IllegalStateException("pluginStoredManifestMismatch:" + row.getKey());
            for (var dependency : manifest.dependencies())
                if (removed.contains(dependency.id()))
                    throw conflict("pluginRequiredBy:" + row.getKey());
        }
        for (String id : removed) requireNoConfiguredReleaseReferences(id);
        return Set.copyOf(removed);
    }

    private void requireNoConfiguredReleaseReferences(String id) {
        var blockers = configuredBlockers(id);
        if (!blockers.isEmpty()) throw conflict(blockers.getFirst());
    }

    private void verifyFixedReleaseCanPublish(Set<String> removedProviders) {
        if (removedProviders.isEmpty()) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("pluginReleasePublicationRequiresTransaction");
        var connection =
                org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        try {
            AdministrationRecoveryBarrier.exclusive(connection);
        } catch (java.sql.SQLException unavailable) {
            throw new IllegalStateException("pluginReleaseRecoveryBarrierUnavailable", unavailable);
        } finally {
            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                    connection, dataSource);
        }
        for (String id : removedProviders) requireNoConfiguredReleaseReferences(id);
    }

    private Set<String> approvedBootstrapSelection(Map<String, ApprovedPlugin> approved) {
        try {
            var selection =
                    new LinkedHashSet<>(
                            bootstrapPluginIds(
                                    approvedGraphPath.getParent().resolve("bootstrap.json"),
                                    mapper));
            for (String id : selection)
                if (!approved.containsKey(id))
                    throw new IllegalArgumentException("pluginBootstrapNotApproved:" + id);
            return Set.copyOf(selection);
        } catch (java.io.IOException unavailable) {
            throw new IllegalStateException("pluginBootstrapManifestUnavailable", unavailable);
        }
    }

    private Set<String> bootstrapClosure(Collection<String> roots) {
        var initial = new LinkedHashSet<String>();
        var graph = graph();
        for (String id : roots) initial.addAll(graph.install(id));
        initial.removeIf(
                id -> !artifacts.containsKey(id)); // Kernel dependencies are already present.
        return Set.copyOf(initial);
    }

    private boolean approvedDatabaseMatches(Map<String, ApprovedPlugin> approved) {
        for (ApprovedPlugin plugin : approved.values()) {
            int present =
                    jdbc.sql(
                                    "SELECT count(*) FROM platform_plugin_artifact WHERE module_id=:id AND version=:version AND digest=:digest")
                            .param("id", plugin.id())
                            .param("version", plugin.version())
                            .param("digest", plugin.digest())
                            .query(Integer.class)
                            .single();
            if (present != 1) return false;
        }
        var installations = installationMap();
        for (Installation installation : installations.values()) {
            ApprovedPlugin packageEntry = approved.get(installation.moduleId());
            if ((installation.installed() || installation.explicitInstall())
                    && packageEntry == null) return false;
            if (packageEntry != null && !packageEntry.digest().equals(installation.digest()))
                return false;
        }
        var ids = new HashSet<>(approved.keySet());
        ids.addAll(installations.keySet());
        return ids.isEmpty()
                || jdbc.sql(
                                        "SELECT count(*) FROM platform_module_state s WHERE s.module_id IN (:ids) AND s.enabled AND NOT EXISTS (SELECT 1 FROM platform_plugin_installation i WHERE i.module_id=s.module_id AND i.installed)")
                                .param("ids", ids)
                                .query(Integer.class)
                                .single()
                        == 0;
    }

    private static Path approvedArchivePath(Path packageDirectory, String id, String version) {
        return packageDirectory
                .resolve(id)
                .resolve("dist")
                .resolve(id + "-" + version + ".lumira-plugin.zip");
    }

    private void validateApprovedInstallations(Map<String, ApprovedPlugin> approved) {
        for (Installation installation : installationMap().values()) {
            if (!installation.installed()) continue;
            ApprovedPlugin packageEntry = approved.get(installation.moduleId());
            if (packageEntry == null || !packageEntry.digest().equals(installation.digest()))
                throw new IllegalStateException("approvedPluginGraphMismatch");
        }
    }

    private void requireApprovedGraph(String graphRevision) {
        tx.executeWithoutResult(
                status -> {
                    jdbc.sql(
                                    "INSERT INTO platform_plugin_graph_approval(id,graph_revision,approved_at,approved_by) VALUES(1,:revision,now(),'runtime-bootstrap') ON CONFLICT(id) DO NOTHING")
                            .param("revision", graphRevision)
                            .update();
                    String approved =
                            jdbc.sql(
                                            "SELECT graph_revision FROM platform_plugin_graph_approval WHERE id=1")
                                    .query(String.class)
                                    .single();
                    if (fixedGraph) {
                        jdbc.sql(
                                        "UPDATE platform_plugin_graph_approval SET graph_revision=:revision,approved_at=now(),approved_by='approved-image' WHERE id=1 AND graph_revision IS DISTINCT FROM :revision")
                                .param("revision", graphRevision)
                                .update();
                    } else if (!approved.equals(graphRevision)) {
                        throw new IllegalStateException("pluginGraphApprovalMismatch");
                    }
                });
    }

    private <T> T withClusterLifecycleLock(Callable<T> work) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            while (true) {
                try (var result =
                        statement.executeQuery(
                                "SELECT pg_try_advisory_lock(" + CLUSTER_LOCK_KEY + ")")) {
                    result.next();
                    if (result.getBoolean(1)) break;
                }
                if (System.nanoTime() >= deadline)
                    throw new IllegalStateException("pluginClusterLifecycleBusy");
                TimeUnit.MILLISECONDS.sleep(100);
            }
            try {
                return work.call();
            } finally {
                statement
                        .executeQuery("SELECT pg_advisory_unlock(" + CLUSTER_LOCK_KEY + ")")
                        .close();
            }
        }
    }

    private boolean graphMatchesLoadedSnapshot() {
        var snapshot = graphSnapshot();
        return localRuntimeReady()
                && !busy
                && nodeLeaseCurrent(snapshot.nodeLeaseDeadline())
                && loadedGraphRevision.equals(snapshot.revision());
    }

    private boolean localRuntimeReady() {
        return ready && heartbeatHealthy;
    }

    private void beginTrafficTransition(Set<String> affected) {
        var snapshot = graphSnapshot(affected);
        trafficTransition =
                new TrafficTransition(
                        Set.copyOf(affected),
                        snapshot.revision(),
                        Set.of(snapshot.approvedRevision()));
    }

    private void allowTransitionApproval(String revision) {
        var transition = trafficTransition;
        if (transition == null) return;
        var approvals = new HashSet<>(transition.approvals());
        approvals.add(revision);
        trafficTransition =
                new TrafficTransition(
                        transition.affected(),
                        transition.unchangedRevision(),
                        Set.copyOf(approvals));
    }

    private boolean changing(String owner) {
        var transition = trafficTransition;
        return transition != null && transition.affected().contains(owner);
    }

    private boolean localRuntimeReady(RuntimeGraphSnapshot snapshot) {
        return localRuntimeReady()
                && nodeLeaseCurrent(snapshot.nodeLeaseDeadline())
                && snapshot.revision().equals(loadedGraphRevision)
                && snapshot.revision().equals(snapshot.approvedRevision());
    }

    private void requireLifecycleChangeAllowed() {
        if (fixedGraph) throw conflict("pluginFixedGraph");
        if (clusterJournal.blocksGraphChange()) throw conflict("pluginClusterOperationActive");
        if (activeNodeCount() > 1 && !clusterEligible())
            throw conflict("pluginClusterLifecycleUnsupported");
        if (busy) throw conflict("pluginBusy");
        if (!graphMatchesLoadedSnapshot()) throw conflict("pluginGraphChanged");
    }

    private void requireOperationTopologyAllowed() {
        if (fixedGraph) throw conflict("pluginFixedGraph");
        if (clusterJournal.blocksGraphChange()) throw conflict("pluginClusterOperationActive");
        if (activeNodeCount() > 1) throw conflict("pluginClusterLifecycleUnsupported");
        var snapshot = graphSnapshot();
        if (!localRuntimeReady()
                || !nodeLeaseCurrent(snapshot.nodeLeaseDeadline())
                || !loadedGraphRevision.equals(snapshot.revision()))
            throw conflict("pluginGraphChanged");
    }

    private String lifecycleBlockReason() {
        if (fixedGraph) return "pluginFixedGraph";
        if (clusterJournal.blocksGraphChange()) return "pluginClusterOperationActive";
        if (activeNodeCount() > 1 && !clusterEligible()) return "pluginClusterLifecycleUnsupported";
        if (busy) return "pluginBusy";
        return graphMatchesLoadedSnapshot() ? null : "pluginGraphChanged";
    }

    @Override
    public void requireChangeAllowed(String moduleId, boolean enabled) {
        if (activeNodeCount() > 1) throw conflict("pluginClusterLifecycleUnsupported");
        requireLifecycleChangeAllowed();
    }

    @Override
    public void recordChange(String moduleId, boolean enabled, String actor) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            AtomicReference<String> committedRevision = new AtomicReference<>();
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            if (readOnly) throw new IllegalStateException("readOnlyModuleChange");
                            committedRevision.set(persistGraphApproval(actor));
                        }

                        @Override
                        public void afterCommit() {
                            publishCommittedGraph(committedRevision.get());
                        }
                    });
        } else {
            AtomicReference<String> committedRevision = new AtomicReference<>();
            tx.executeWithoutResult(status -> committedRevision.set(persistGraphApproval(actor)));
            publishCommittedGraph(committedRevision.get());
        }
    }

    private String persistGraphApproval(String actor) {
        String currentRevision = revision();
        allowTransitionApproval(currentRevision);
        int updated =
                jdbc.sql(
                                "UPDATE platform_plugin_graph_approval SET graph_revision=:revision,approved_at=now(),approved_by=:actor WHERE id=1")
                        .param("revision", currentRevision)
                        .param("actor", actor)
                        .update();
        if (updated != 1) throw new IllegalStateException("pluginGraphApprovalUnavailable");
        return currentRevision;
    }

    /**
     * Only publishes local state after commit; the heartbeat executor writes the node row later.
     */
    private void publishCommittedGraph(String committedRevision) {
        if (committedRevision == null) {
            ready = false;
            return;
        }
        var transition = trafficTransition;
        if (transition != null)
            for (String id : transition.affected()) {
                var handle = handles.get(id);
                if (handle != null) handle.generation = storedGeneration(id);
            }
        loadedGraphRevision = committedRevision;
        ready = heartbeatHealthy;
        if (!nodeRegistered) return;
        try {
            heartbeatExecutor.execute(this::heartbeatTick);
        } catch (RejectedExecutionException shuttingDown) {
            ready = false;
            heartbeatHealthy = false;
        }
    }

    @Override
    public Health health() {
        if (!localRuntimeReady())
            return Health.down().withDetail("reason", "pluginRuntimeNotReady").build();
        try {
            var transition = trafficTransition;
            if (transition != null && isReadyForTraffic())
                return Health.up().withDetail("changingPlugins", transition.affected()).build();
            RuntimeGraphSnapshot snapshot = graphSnapshot();
            String currentRevision = snapshot.revision();
            if (!currentRevision.equals(loadedGraphRevision))
                return Health.down().withDetail("reason", "pluginGraphChanged").build();
            String approvedRevision = snapshot.approvedRevision();
            if (!currentRevision.equals(approvedRevision))
                return Health.down().withDetail("reason", "pluginGraphApprovalMismatch").build();
            List<RuntimeNode> nodes = activeNodes();
            RuntimeNode localNode =
                    nodes.stream()
                            .filter(node -> node.nodeId().equals(nodeId))
                            .findFirst()
                            .orElse(null);
            if (localNode == null)
                return Health.down().withDetail("reason", "pluginRuntimeNodeUnavailable").build();
            if (!localNode.ready() || !currentRevision.equals(localNode.graphRevision()))
                return Health.down().withDetail("reason", "pluginRuntimeNodeNotReady").build();
            long unreadyPeers =
                    nodes.stream()
                            .filter(node -> !node.nodeId().equals(nodeId))
                            .filter(
                                    node ->
                                            !node.ready()
                                                    || !currentRevision.equals(
                                                            node.graphRevision()))
                            .count();
            return Health.up()
                    .withDetail("fixedGraph", fixedGraph)
                    .withDetail("nodes", nodes.size())
                    .withDetail("unreadyPeers", unreadyPeers)
                    .build();
        } catch (RuntimeException failure) {
            return Health.down().withDetail("reason", "pluginRuntimeUnavailable").build();
        }
    }

    TrafficScope openTrafficScope() {
        var scope = new TrafficScope(trafficScope.get());
        trafficScope.set(scope);
        return scope;
    }

    public boolean isReadyForTraffic() {
        if (!localRuntimeReady()) return false;
        var scope = trafficScope.get();
        String revision = loadedGraphRevision;
        var transition = trafficTransition;
        if (scope != null
                && scope.check != null
                && revision.equals(scope.check.revision())
                && transition == scope.check.transition()
                && (!scope.check.ready() || nodeLeaseCurrent(scope.check.nodeLeaseDeadline())))
            return scope.check.ready();
        var result = checkTrafficReadiness();
        if (scope != null)
            scope.check =
                    new TrafficCheck(
                            revision, transition, result.ready(), result.nodeLeaseDeadline());
        return result.ready();
    }

    private static boolean nodeLeaseCurrent(long deadline) {
        return System.nanoTime() - deadline < 0;
    }

    private TrafficReadiness checkTrafficReadiness() {
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                var transition = trafficTransition;
                var snapshot = graphSnapshot();
                if (localRuntimeReady(snapshot))
                    return new TrafficReadiness(true, snapshot.nodeLeaseDeadline());
                if (transition != null) {
                    var unchanged = graphSnapshot(transition.affected());
                    if (transition == trafficTransition)
                        return new TrafficReadiness(
                                localRuntimeReady()
                                        && nodeLeaseCurrent(unchanged.nodeLeaseDeadline())
                                        && unchanged
                                                .revision()
                                                .equals(transition.unchangedRevision())
                                        && transition
                                                .approvals()
                                                .contains(unchanged.approvedRevision()),
                                unchanged.nodeLeaseDeadline());
                } else if (trafficTransition == null) {
                    break;
                }
            }
            return new TrafficReadiness(false, 0);
        } catch (RuntimeException failure) {
            return new TrafficReadiness(false, 0);
        }
    }

    private void requireRuntimeReady() {
        if (!isReadyForTraffic())
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "pluginRuntimeNotReady");
    }

    private void registerCatalog() {
        registry.replacePlugins(
                orderAll().stream()
                        .map(
                                id -> {
                                    var m = artifacts.get(id).manifest();
                                    return new PlatformModule(
                                            m.id(),
                                            m.name(),
                                            m.version(),
                                            m.dependencies().stream()
                                                    .map(PluginManifest.Dependency::id)
                                                    .toList());
                                })
                        .toList());

        var installedManifests =
                orderAll().stream()
                        .filter(this::installed)
                        .map(id -> artifacts.get(id).manifest())
                        .toList();
        PluginPermissions.replaceAll(catalogForManifests(installedManifests));
    }

    static Map<String, List<PluginPermissions.CatalogGroup>> catalogForManifests(
            List<PluginManifest> installedManifests) {
        var pluginCatalog = new TreeMap<String, List<PluginPermissions.CatalogGroup>>();
        for (var owner : installedManifests) {
            var groups = new ArrayList<PluginPermissions.CatalogGroup>();
            for (var group : owner.permissionGroups()) {
                var codes =
                        group.permissions().stream()
                                .map(PluginManifest.Permission::code)
                                .collect(java.util.stream.Collectors.toSet());
                var pages =
                        owner.routes().stream()
                                .filter(route -> codes.contains(route.permission()))
                                .filter(
                                        route ->
                                                !route.operations().isEmpty()
                                                        ? codes.containsAll(
                                                                route.operations().values())
                                                        : true)
                                .map(
                                        route ->
                                                new PluginPermissions.Page(
                                                        route.id(),
                                                        "/dashboard/" + route.id(),
                                                        route.label(),
                                                        route.group(),
                                                        route.order(),
                                                        route.permission(),
                                                        List.copyOf(route.operations().values()),
                                                        route.global() ? "global" : "scoped",
                                                        route.operations().entrySet().stream()
                                                                .collect(
                                                                        java.util.stream.Collectors
                                                                                .toUnmodifiableMap(
                                                                                        Map.Entry
                                                                                                ::getValue,
                                                                                        operation ->
                                                                                                route.scopedOperations()
                                                                                                                .contains(
                                                                                                                        operation
                                                                                                                                .getKey())
                                                                                                        ? "scoped"
                                                                                                        : "global"))))
                                .distinct()
                                .sorted(
                                        Comparator.comparing(PluginPermissions.Page::id)
                                                .thenComparing(PluginPermissions.Page::title))
                                .toList();
                var permissions =
                        group.permissions().stream()
                                .map(
                                        permission ->
                                                new PluginPermissions.Permission(
                                                        permission.code(),
                                                        permission.label(),
                                                        permission.requires()))
                                .toList();
                groups.add(
                        new PluginPermissions.CatalogGroup(
                                owner.id() + ":" + group.id(), group.title(), pages, permissions));
            }
            pluginCatalog.put(owner.id(), groups);
        }
        return Map.copyOf(pluginCatalog);
    }

    private List<String> orderAll() {
        return graph().catalogOrder(artifacts.keySet().stream().sorted().toList()).stream()
                .filter(artifacts::containsKey)
                .toList();
    }

    private PluginGraph graph() {
        var nodes = new ArrayList<PluginGraph.Node>();
        for (var m : registry.modules())
            if (!registry.isPlugin(m.id()))
                nodes.add(
                        new PluginGraph.Node(
                                m.id(),
                                m.version(),
                                m.dependencies().stream()
                                        .map(
                                                d ->
                                                        new PluginManifest.Dependency(
                                                                d, registry.require(d).version()))
                                        .toList(),
                                m.core(),
                                true,
                                true));
        var installations = installationMap();
        for (var a : artifacts.values()) {
            var i = installations.get(a.manifest().id());
            var m = a.manifest();
            nodes.add(
                    new PluginGraph.Node(
                            m.id(),
                            m.version(),
                            m.dependencies(),
                            false,
                            i != null && i.installed(),
                            i != null && i.explicitInstall()));
        }
        return new PluginGraph(nodes);
    }

    private Map<String, Installation> installationMap() {
        var out = new TreeMap<String, Installation>();
        jdbc.sql(
                        "SELECT module_id,digest,installed,explicit_install FROM platform_plugin_installation")
                .query(Installation.class)
                .list()
                .forEach(i -> out.put(i.moduleId(), i));
        return out;
    }

    private boolean installed(String id) {
        var i = installationMap().get(id);
        return i != null && i.installed();
    }

    private boolean storedEnabled(String id) {
        return jdbc.sql("SELECT enabled FROM platform_module_state WHERE module_id=:id")
                .param("id", id)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    private boolean isModuleEnabled(String moduleId) {
        return states.isEnabled(moduleId);
    }

    private Map<String, Boolean> parseModuleStates(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) return Map.of();
        try {
            var tree = mapper.readTree(json);
            if (!tree.isArray()) return Map.of();
            var map = new HashMap<String, Boolean>();
            for (var elem : tree) {
                if (elem.isArray() && elem.size() >= 2) {
                    map.put(elem.get(0).asText(), elem.get(1).asBoolean());
                }
            }
            return Map.copyOf(map);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String revision() {
        return graphSnapshot().revision();
    }

    /** Reads all revision inputs and its approval in one PostgreSQL statement snapshot. */
    private RuntimeGraphSnapshot graphSnapshot() {
        return graphSnapshot(Set.of());
    }

    private static final String STEADY_STATE_GRAPH_SIG_QUERY =
            """
            WITH artifact_rows_meta AS (
                SELECT (
                    COALESCE((SELECT count(*) || ':' || COALESCE(sum(hashtext(module_id || ':' || version || ':' || digest)), 0)
                        FROM platform_plugin_artifact), '0:0')
                    || '|' ||
                    COALESCE((SELECT count(*) || ':' || COALESCE(sum(hashtext(module_id || ':' || digest || ':' || installed::text || ':' || explicit_install::text)), 0)
                        FROM platform_plugin_installation), '0:0')
                    || '|' ||
                    COALESCE((SELECT count(*) || ':' || COALESCE(sum(version), 0) || ':' || COALESCE(sum(hashtext(module_id || ':' || enabled::text || ':' || version::text)), 0)
                        FROM platform_module_state), '0:0:0')
                ) AS sig
            )
            SELECT
                artifact_rows_meta.sig AS current_sig,
                COALESCE((SELECT graph_revision
                    FROM platform_plugin_graph_approval WHERE id = 1), '')
                    AS approved_revision,
                COALESCE((SELECT GREATEST(0,
                    FLOOR(EXTRACT(EPOCH FROM (heartbeat_at + interval '30 seconds'
                        - clock_timestamp())) * 1000000000))::bigint
                    FROM platform_plugin_runtime_node WHERE node_id = :node), 0)
                    AS node_lease_remaining_nanos
            FROM artifact_rows_meta
            """;

    private record SteadyStateSig(
            String currentSig, String approvedRevision, long nodeLeaseRemainingNanos) {}

    private RuntimeGraphSnapshot graphSnapshot(Set<String> excluded) {
        CachedSnapshot cached = snapshotCache.get(excluded);
        String expectedSig = cached != null ? cached.sig() : "";
        if (excluded.isEmpty()
                && cached != null
                && cached.sig() != null
                && !cached.sig().isEmpty()) {
            // Start the monotonic deadline before the round trip. A slow connection can shorten a
            // confirmed lease, never extend it past the database's remaining heartbeat lifetime.
            long queryStarted = System.nanoTime();
            SteadyStateSig sigRow =
                    jdbc.sql(STEADY_STATE_GRAPH_SIG_QUERY)
                            .param("node", nodeId)
                            .query(
                                    (rs, row) ->
                                            new SteadyStateSig(
                                                    rs.getString("current_sig"),
                                                    rs.getString("approved_revision"),
                                                    rs.getLong("node_lease_remaining_nanos")))
                            .single();
            if (sigRow.currentSig() != null && sigRow.currentSig().equals(cached.sig())) {
                ModuleStateStore.populateScope(cached.moduleEnabled());
                return new RuntimeGraphSnapshot(
                        cached.revision(),
                        sigRow.approvedRevision(),
                        queryStarted
                                + Math.min(
                                        NODE_STALE_AFTER.toNanos(),
                                        sigRow.nodeLeaseRemainingNanos()));
            }
            expectedSig = "";
        }
        // Start the monotonic deadline before the round trip. A slow connection can shorten a
        // confirmed lease, never extend it past the database's remaining heartbeat lifetime.
        long queryStarted = System.nanoTime();
        return jdbc.sql(
                        """
                            WITH artifact_rows_meta AS MATERIALIZED (
                                SELECT (
                                    COALESCE((SELECT count(*) || ':' || COALESCE(sum(hashtext(module_id || ':' || version || ':' || digest)), 0)
                                        FROM platform_plugin_artifact WHERE module_id NOT IN (:excluded)), '0:0')
                                    || '|' ||
                                    COALESCE((SELECT count(*) || ':' || COALESCE(sum(hashtext(module_id || ':' || digest || ':' || installed::text || ':' || explicit_install::text)), 0)
                                        FROM platform_plugin_installation WHERE module_id NOT IN (:excluded)), '0:0')
                                    || '|' ||
                                    COALESCE((SELECT count(*) || ':' || COALESCE(sum(version), 0) || ':' || COALESCE(sum(hashtext(module_id || ':' || enabled::text || ':' || version::text)), 0)
                                        FROM platform_module_state WHERE module_id NOT IN (:excluded)), '0:0:0')
                                ) AS sig
                            ), artifact_rows AS (
                                SELECT CASE WHEN (SELECT sig FROM artifact_rows_meta) = :expectedSig THEN NULL
                                    ELSE COALESCE(
                                        jsonb_agg(jsonb_build_array(module_id, version, digest)
                                            ORDER BY module_id, version, digest),
                                        '[]'::jsonb)::text END AS value
                                FROM platform_plugin_artifact
                                WHERE module_id NOT IN (:excluded)
                                    AND (:expectedSig = '' OR (SELECT sig FROM artifact_rows_meta) != :expectedSig)
                            ), installation_rows AS (
                                SELECT CASE WHEN (SELECT sig FROM artifact_rows_meta) = :expectedSig THEN NULL
                                    ELSE COALESCE(
                                        jsonb_agg(jsonb_build_array(module_id, digest, installed, explicit_install)
                                            ORDER BY module_id),
                                        '[]'::jsonb)::text END AS value
                                FROM platform_plugin_installation
                                WHERE module_id NOT IN (:excluded)
                                    AND (:expectedSig = '' OR (SELECT sig FROM artifact_rows_meta) != :expectedSig)
                            ), module_rows AS (
                                SELECT CASE WHEN (SELECT sig FROM artifact_rows_meta) = :expectedSig THEN NULL
                                    ELSE COALESCE(
                                        jsonb_agg(jsonb_build_array(module_id, enabled, version)
                                            ORDER BY module_id),
                                        '[]'::jsonb)::text END AS value
                                FROM platform_module_state
                                WHERE module_id NOT IN (:excluded)
                                    AND (:expectedSig = '' OR (SELECT sig FROM artifact_rows_meta) != :expectedSig)
                            )
                            SELECT
                                artifact_rows_meta.sig AS current_sig,
                                artifact_rows.value AS artifacts,
                                installation_rows.value AS installations,
                                module_rows.value AS module_states,
                                COALESCE((SELECT graph_revision
                                    FROM platform_plugin_graph_approval WHERE id = 1), '')
                                    AS approved_revision,
                                COALESCE((SELECT GREATEST(0,
                                    FLOOR(EXTRACT(EPOCH FROM (heartbeat_at + interval '30 seconds'
                                        - clock_timestamp())) * 1000000000))::bigint
                                    FROM platform_plugin_runtime_node WHERE node_id=:node), 0)
                                    AS node_lease_remaining_nanos
                            FROM artifact_rows_meta CROSS JOIN artifact_rows CROSS JOIN installation_rows CROSS JOIN module_rows
                            """)
                .param("excluded", excluded.isEmpty() ? Set.of("-") : excluded)
                .param("expectedSig", expectedSig)
                .param("node", nodeId)
                .query(
                        (rs, row) -> {
                            String currentSig = rs.getString("current_sig");
                            String revision;
                            if (cached != null
                                    && currentSig != null
                                    && currentSig.equals(cached.sig())) {
                                revision = cached.revision();
                                if (excluded.isEmpty()) {
                                    ModuleStateStore.populateScope(cached.moduleEnabled());
                                }
                            } else {
                                String artifacts = rs.getString("artifacts");
                                if (artifacts == null) artifacts = "[]";
                                String installations = rs.getString("installations");
                                if (installations == null) installations = "[]";
                                String moduleStates = rs.getString("module_states");
                                if (moduleStates == null) moduleStates = "[]";
                                revision =
                                        PluginArchive.digest(
                                                mapper.writeValueAsBytes(
                                                        new RuntimeRevisionSnapshot(
                                                                artifacts,
                                                                installations,
                                                                moduleStates)));
                                Map<String, Boolean> moduleEnabled =
                                        parseModuleStates(moduleStates);
                                if (currentSig != null) {
                                    if (snapshotCache.size() > 16) snapshotCache.clear();
                                    var newSnapshot =
                                            new CachedSnapshot(
                                                    Set.copyOf(excluded),
                                                    currentSig,
                                                    revision,
                                                    artifacts,
                                                    installations,
                                                    moduleStates,
                                                    moduleEnabled);
                                    snapshotCache.put(Set.copyOf(excluded), newSnapshot);
                                    if (excluded.isEmpty()) {
                                        ModuleStateStore.populateScope(moduleEnabled);
                                    }
                                }
                            }
                            return new RuntimeGraphSnapshot(
                                    revision,
                                    rs.getString("approved_revision"),
                                    queryStarted
                                            + Math.min(
                                                    NODE_STALE_AFTER.toNanos(),
                                                    rs.getLong("node_lease_remaining_nanos")));
                        })
                .single();
    }

    public List<View> list(String actor) {
        read(actor);
        requireRuntimeReady();
        return views();
    }

    public List<View> views() {
        String lifecycleBlockReason = lifecycleBlockReason();
        var installations = installationMap();
        Map<String, Boolean> enabledFlags = new HashMap<>();
        jdbc.sql("SELECT module_id,enabled FROM platform_module_state")
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getBoolean(2)))
                .list()
                .forEach(entry -> enabledFlags.put(entry.getKey(), entry.getValue()));
        return artifacts.values().stream()
                .sorted(Comparator.comparing(a -> a.manifest().id()))
                .map(
                        a -> {
                            var i = installations.get(a.manifest().id());
                            boolean flag = enabledFlags.getOrDefault(a.manifest().id(), false);
                            boolean in = i != null && i.installed();
                            return new View(
                                    a.manifest().id(),
                                    a.manifest().name(),
                                    a.manifest().version(),
                                    a.digest(),
                                    in,
                                    in && flag && registry.isLive(a.manifest().id()),
                                    i != null && i.explicitInstall(),
                                    !in
                                            ? "UNINSTALLED"
                                            : handles.containsKey(a.manifest().id())
                                                    ? "RUNNING"
                                                    : flag ? "FAILED" : "STOPPED",
                                    a.manifest(),
                                    lifecycleBlockReason == null,
                                    lifecycleBlockReason);
                        })
                .toList();
    }

    public List<View> frontend() {
        requireRuntimeReady();
        return views().stream().filter(View::enabled).filter(view -> !changing(view.id())).toList();
    }

    public List<View> importPackage(String actor, byte[] bytes) throws Exception {
        guard.requireGlobal(actor, PlatformPageId.MODULES.accessPermission());
        guard.requireGlobal(actor, PlatformOperation.MODULE_INSTALL.permission());
        if (activeNodeCount() > 1) throw conflict("pluginClusterLifecycleUnsupported");
        requireLifecycleChangeAllowed();
        return withClusterLifecycleLock(
                () -> {
                    synchronized (lock) {
                        requireLifecycleChangeAllowed();
                        if (activeNodeCount() > 1)
                            throw conflict("pluginClusterLifecycleUnsupported");
                        var before = new HashMap<>(artifacts);
                        try {
                            AtomicReference<String> committedRevision = new AtomicReference<>();
                            tx.executeWithoutResult(
                                    status -> {
                                        try {
                                            var imported = importArchive(bytes, 0);
                                            // Newly imported roots must be immediately installable;
                                            // obsolete, uninstalled catalog entries need not be.
                                            graph().install(imported);
                                            orderAll();
                                            registerCatalog();
                                            if (jdbc.sql(
                                                                            "SELECT count(*) FROM platform_plugin_artifact WHERE digest=:digest")
                                                                    .param(
                                                                            "digest",
                                                                            PluginArchive.digest(
                                                                                    bytes))
                                                                    .query(Integer.class)
                                                                    .single()
                                                            > 0
                                                    && !before.values().stream()
                                                            .anyMatch(
                                                                    a ->
                                                                            a.digest()
                                                                                    .equals(
                                                                                            PluginArchive
                                                                                                    .digest(
                                                                                                            bytes))))
                                                audit.append(
                                                        actor,
                                                        "plugin.imported",
                                                        UUID.nameUUIDFromBytes(
                                                                PluginArchive.digest(bytes)
                                                                        .getBytes(
                                                                                java.nio.charset
                                                                                        .StandardCharsets
                                                                                        .UTF_8)));
                                            committedRevision.set(persistGraphApproval(actor));
                                        } catch (ResponseStatusException e) {
                                            throw e;
                                        } catch (Exception e) {
                                            throw new ResponseStatusException(
                                                    HttpStatus.BAD_REQUEST,
                                                    e instanceof IllegalArgumentException
                                                            ? e.getMessage()
                                                            : "invalidPluginPackage",
                                                    e);
                                        }
                                    });
                            publishCommittedGraph(committedRevision.get());
                        } catch (Exception e) {
                            artifacts.clear();
                            artifacts.putAll(before);
                            try {
                                registerCatalog();
                            } catch (Exception recovery) {
                                e.addSuppressed(recovery);
                                ready = false;
                            }
                            throw e;
                        }
                        return views();
                    }
                });
    }

    private String importArchive(byte[] bytes, int depth) throws Exception {
        if (depth > 16) throw conflict("pluginDependencyCycle");
        var a = artifactStore.importArchive(bytes, mapper);
        for (String name : a.manifest().files().keySet())
            if (name.startsWith("dependencies/") && name.endsWith(".lumira-plugin.zip"))
                importArchive(Files.readAllBytes(a.directory().resolve(name)), depth + 1);
        var sameVersion =
                jdbc.sql(
                                "SELECT digest FROM platform_plugin_artifact WHERE module_id=:id AND version=:version")
                        .param("id", a.manifest().id())
                        .param("version", a.manifest().version())
                        .query(String.class)
                        .optional();
        if (sameVersion.isPresent() && !sameVersion.get().equals(a.digest()))
            throw conflict("pluginVersionConflict:" + a.manifest().id());
        var previous = artifacts.get(a.manifest().id());
        if (previous != null
                && !previous.digest().equals(a.digest())
                && (installed(a.manifest().id())
                        || previous.manifest().version().equals(a.manifest().version())))
            throw conflict("pluginVersionConflict:" + a.manifest().id());
        artifacts.put(a.manifest().id(), a);
        jdbc.sql(
                        "INSERT INTO platform_plugin_artifact(digest,module_id,version,manifest) VALUES(:digest,:id,:version,CAST(:manifest AS jsonb)) ON CONFLICT(digest) DO NOTHING")
                .param("digest", a.digest())
                .param("id", a.manifest().id())
                .param("version", a.manifest().version())
                .param("manifest", mapper.writeValueAsString(a.manifest()))
                .update();
        return a.manifest().id();
    }

    public Plan plan(String actor, String id, String kind) {
        return plan(actor, id, kind, "PRESERVE");
    }

    public Plan plan(String actor, String id, String kind, String dataMode) {
        requireLifecyclePermission(actor, kind);
        requireRuntimeReady();
        synchronized (lock) {
            var plan = makePlan(id, kind, dataMode);
            if (!plan.enable().isEmpty())
                guard.requireGlobal(actor, PlatformOperation.MODULE_UPDATE.permission());
            return plan;
        }
    }

    private Plan makePlan(String id, String kind, String dataMode) {
        if (!Set.of("PRESERVE", "DELETE").contains(dataMode)
                || kind.equals("INSTALL") && !dataMode.equals("PRESERVE"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidPluginOperation");
        var artifact = artifacts.get(id);
        boolean supported = artifact != null && !artifact.manifest().dataSets().isEmpty();
        List<PluginDataRemoval.Count> data = List.of();
        var install = new ArrayList<String>();
        var reuse = new ArrayList<String>();
        var enable = new ArrayList<String>();
        var remove = new ArrayList<String>();
        var blockers = new ArrayList<String>();
        var referenceBlockers = new ArrayList<String>();
        String lifecycleBlock = lifecycleBlockReason();
        if (lifecycleBlock != null) blockers.add(lifecycleBlock);
        if (activeNodeCount() > 1 && (dataMode.equals("DELETE") || id.equals("cluster-runtime")))
            blockers.add(
                    id.equals("cluster-runtime")
                            ? "requiredModule"
                            : "pluginDataDeletionUnsupported");
        try {
            if (kind.equals("INSTALL")) {
                if (!artifacts.containsKey(id))
                    throw new IllegalArgumentException("pluginNotFound");
                for (String item : graph().install(id)) {
                    if (!artifacts.containsKey(item) || installed(item)) {
                        reuse.add(item);
                        if (artifacts.containsKey(item) && !storedEnabled(item)) enable.add(item);
                    } else {
                        validateNavigation(artifacts.get(item).manifest());
                        validateEndpoints(artifacts.get(item));
                        install.add(item);
                    }
                }
            } else if (kind.equals("UNINSTALL")) {
                try {
                    remove.addAll(graph().uninstall(id));
                } catch (IllegalArgumentException dependency) {
                    blockers.add(dependency.getMessage());
                }
                // Installed consumers must not hide retained business references from preflight.
                // Keep the removal list empty when graph validation blocks this explicit target.
                if (installed(id)) referenceBlockers.addAll(configuredBlockers(id));
                blockers.addAll(referenceBlockers);
                if (dataMode.equals("DELETE")) {
                    if (!supported)
                        throw new IllegalArgumentException("pluginDataDeletionUnsupported");
                    data = PluginDataRemoval.counts(jdbc, artifact.manifest().dataSets());
                }
            } else throw new IllegalArgumentException("invalidPluginOperation");
        } catch (IllegalArgumentException e) {
            blockers.add(e.getMessage());
        } catch (org.springframework.dao.DataAccessException e) {
            blockers.add("pluginDataUnavailable");
        }
        String revision =
                PluginArchive.digest(
                        (revision()
                                        + id
                                        + kind
                                        + dataMode
                                        + mapper.writeValueAsString(data)
                                        + mapper.writeValueAsString(
                                                referenceBlockers.stream()
                                                        .distinct()
                                                        .sorted()
                                                        .toList()))
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Plan(
                id, kind, revision, install, reuse, enable, remove, blockers, dataMode, supported,
                data);
    }

    public Operation execute(String actor, String id, String kind, Execute input) {
        requireLifecyclePermission(actor, kind);
        requireRuntimeReady();
        if (activeNodeCount() > 1 && clusterEligible())
            return executeCluster(actor, id, kind, input);
        requireLifecycleChangeAllowed();
        synchronized (lock) {
            if (!ready || busy) throw conflict("pluginBusy");
            var plan =
                    makePlan(
                            id,
                            kind,
                            input == null || input.dataMode() == null
                                    ? "PRESERVE"
                                    : input.dataMode());
            requireLifecyclePermissions(actor, plan);
            if (input == null || !plan.revision().equals(input.revision()))
                throw conflict("staleRecord");
            if (!plan.blockers().isEmpty()) throw conflict(plan.blockers().getFirst());
            if (plan.dataMode().equals("DELETE") && !id.equals(input.confirmation()))
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "pluginDataConfirmationRequired");
            UUID op = UUID.randomUUID();
            jdbc.sql(
                            "INSERT INTO platform_plugin_operation(id,module_id,kind,status,step,data_mode,owner_node_id,lease_expires_at) VALUES(:op,:id,:kind,'QUEUED','preflight complete',:mode,:owner,now()+interval '45 seconds')")
                    .param("op", op)
                    .param("id", id)
                    .param("kind", kind)
                    .param("mode", plan.dataMode())
                    .param("owner", nodeId)
                    .update();
            busy = true;
            updateNodeState(true, loadedGraphRevision);
            try {
                executor.submit(() -> runOperation(actor, op, plan));
            } catch (RuntimeException failure) {
                busy = false;
                step(op, "FAILED", "queue operation failed", "pluginOperationInterrupted");
                updateNodeState(true, loadedGraphRevision);
                throw failure;
            }
            return operation(actor, op);
        }
    }

    private Operation executeCluster(String actor, String id, String kind, Execute input) {
        try {
            return withClusterLifecycleLock(
                    () -> {
                        synchronized (lock) {
                            requireLifecycleChangeAllowed();
                            var plan =
                                    makePlan(
                                            id,
                                            kind,
                                            input == null || input.dataMode() == null
                                                    ? "PRESERVE"
                                                    : input.dataMode());
                            requireLifecyclePermissions(actor, plan);
                            if (input == null || !plan.revision().equals(input.revision()))
                                throw conflict("staleRecord");
                            if (!plan.blockers().isEmpty())
                                throw conflict(plan.blockers().getFirst());
                            UUID operationId = UUID.randomUUID();
                            var operation =
                                    tx.execute(
                                            status -> {
                                                requireLifecyclePermissions(actor, plan);
                                                UUID actorId =
                                                        jdbc.sql(
                                                                        "SELECT id FROM platform_user WHERE username=:actor AND enabled AND deleted_at IS NULL")
                                                                .param("actor", actor)
                                                                .query(UUID.class)
                                                                .single();
                                                var changed =
                                                        clusterAffected(
                                                                plan.install(),
                                                                plan.enable(),
                                                                plan.remove(),
                                                                id);
                                                var digests = new TreeMap<String, String>();
                                                for (String item : changed)
                                                    digests.put(item, artifacts.get(item).digest());
                                                var decision =
                                                        new ClusterOperationJournal.Decision(
                                                                actorId,
                                                                ClusterOperationJournal.Kind
                                                                        .valueOf(kind),
                                                                id,
                                                                projectedRevision(plan),
                                                                digests,
                                                                plan.install(),
                                                                plan.enable(),
                                                                plan.remove());
                                                String base = revision();
                                                var participants = clusterJournal.activate(base);
                                                return clusterJournal.begin(
                                                        operationId,
                                                        nodeId,
                                                        UUID.randomUUID(),
                                                        base,
                                                        decision,
                                                        participants,
                                                        true);
                                            });
                            return clusterOperation(operation);
                        }
                    });
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("pluginClusterLifecycleBusy", failure);
        }
    }

    private String projectedRevision(Plan plan) {
        var installationChanges = new TreeMap<String, Installation>();
        var flagChanges = new TreeMap<String, Boolean>();
        if (plan.kind().equals("INSTALL")) {
            for (String id : plan.install()) {
                installationChanges.put(
                        id,
                        new Installation(
                                id, artifacts.get(id).digest(), true, id.equals(plan.moduleId())));
                flagChanges.put(id, true);
            }
            String id = plan.moduleId();
            installationChanges.put(
                    id, new Installation(id, artifacts.get(id).digest(), true, true));
            for (String enabled : plan.enable()) flagChanges.put(enabled, true);
        } else
            for (String id : plan.remove()) {
                installationChanges.put(
                        id, new Installation(id, artifacts.get(id).digest(), false, false));
                flagChanges.put(id, false);
            }
        return PluginGraphProjection.project(
                jdbc, mapper, installationChanges.values(), flagChanges);
    }

    private static Set<String> clusterAffected(
            List<String> install, List<String> enable, List<String> remove, String root) {
        var affected = new HashSet<String>();
        affected.addAll(install);
        affected.addAll(enable);
        affected.addAll(remove);
        affected.add(root);
        return Set.copyOf(affected);
    }

    private static Set<String> clusterAffected(ClusterOperationJournal.Operation operation) {
        var d = operation.decision();
        return clusterAffected(d.install(), d.enable(), d.remove(), d.moduleId());
    }

    private static Operation clusterOperation(ClusterOperationJournal.Operation operation) {
        String status =
                switch (operation.phase()) {
                    case COMPLETED -> "SUCCEEDED";
                    case ABORTED -> "FAILED";
                    default -> "RUNNING";
                };
        return new Operation(
                operation.id(),
                operation.decision().moduleId(),
                operation.decision().kind().name(),
                status,
                operation.phase().name(),
                operation.failureCode(),
                "PRESERVE");
    }

    private Plan clusterPlan(ClusterOperationJournal.Operation operation) {
        var d = operation.decision();
        return new Plan(
                d.moduleId(),
                d.kind().name(),
                operation.baseRevision(),
                d.install(),
                List.of(),
                d.enable(),
                d.remove(),
                List.of(),
                "PRESERVE",
                false,
                List.of());
    }

    private final class ClusterParticipant implements ClusterRuntimeCoordinator.Participant {
        @Override
        public boolean recover(ClusterOperationJournal.Operation operation) throws Exception {
            synchronized (lock) {
                if (clusterFenced || ready || !handles.isEmpty() || !nodeRegistered) return false;
                if (!Boolean.TRUE.equals(
                        jdbc.sql(
                                        "SELECT EXISTS(SELECT 1 FROM plugin_cluster_runtime_operation "
                                                + "WHERE id=:id AND lease_expires_at<=clock_timestamp() "
                                                + "AND phase NOT IN ('COMPLETED','ABORTED'))")
                                .param("id", operation.id())
                                .query(Boolean.class)
                                .single())) return false;
                String expected =
                        operation.phase() == ClusterOperationJournal.Phase.COMMITTED
                                ? operation.decision().targetRevision()
                                : operation.baseRevision();
                var snapshot = graphSnapshot();
                if (!snapshot.revision().equals(expected)
                        || !snapshot.approvedRevision().equals(expected))
                    throw conflict("pluginGraphChanged");
                hydrateClusterArtifacts(operation, true);
                return true;
            }
        }

        @Override
        public void fenced(ClusterOperationJournal.Operation operation) {
            synchronized (lock) {
                clusterFenced = true;
                ready = false;
                heartbeatHealthy = false;
                busy = true;
            }
        }

        @Override
        public void prepare(ClusterOperationJournal.Operation operation) throws Exception {
            synchronized (lock) {
                if (!operation.baseRevision().equals(revision())
                        || !operation.baseRevision().equals(loadedGraphRevision))
                    throw conflict("pluginGraphChanged");
                for (var item : operation.decision().digests().entrySet()) {
                    String json =
                            jdbc.sql(
                                            "SELECT manifest::text FROM platform_plugin_artifact WHERE module_id=:id AND digest=:digest")
                                    .param("id", item.getKey())
                                    .param("digest", item.getValue())
                                    .query(String.class)
                                    .single();
                    var manifest = mapper.readValue(json, PluginManifest.class);
                    var current = handles.get(item.getKey());
                    if (current != null && !current.digest.equals(item.getValue()))
                        throw conflict("pluginGraphChanged");
                    artifacts.put(item.getKey(), artifactStore.stored(item.getValue(), manifest));
                }
                var d = operation.decision();
                if (d.kind() == ClusterOperationJournal.Kind.UNINSTALL) {
                    if (!graph().uninstall(d.moduleId()).equals(d.remove()))
                        throw conflict("pluginGraphChanged");
                    requireNoConfiguredDependencies(d.moduleId());
                } else {
                    var expected = makePlan(d.moduleId(), "INSTALL", "PRESERVE");
                    if (!expected.install().equals(d.install())
                            || !expected.enable().equals(d.enable()))
                        throw conflict("pluginGraphChanged");
                    for (String id : d.install()) {
                        validateNavigation(artifacts.get(id).manifest());
                        validateEndpoints(artifacts.get(id));
                    }
                }
                beginTrafficTransition(clusterAffected(operation));
                allowTransitionApproval(d.targetRevision());
                busy = true;
            }
        }

        @Override
        public void drain(ClusterOperationJournal.Operation operation) throws Exception {
            synchronized (lock) {
                for (String id : clusterAffected(operation)) PluginRuntime.this.drain(id);
            }
        }

        @Override
        public void stage(ClusterOperationJournal.Operation operation) throws Exception {
            synchronized (lock) {
                var d = operation.decision();
                for (String id : d.remove()) unload(id);
                for (String id : d.install()) load(id);
                for (String id : d.enable()) load(id);
            }
        }

        @Override
        public void commit(ClusterOperationJournal.Operation operation) throws Exception {
            withClusterLifecycleLock(
                    () -> {
                        synchronized (lock) {
                            var plan = clusterPlan(operation);
                            fenceLifecycle(clusterAffected(operation));
                            var connection =
                                    org.springframework.jdbc.datasource.DataSourceUtils
                                            .getConnection(dataSource);
                            try {
                                AdministrationRecoveryBarrier.exclusive(connection);
                            } finally {
                                org.springframework.jdbc.datasource.DataSourceUtils
                                        .releaseConnection(connection, dataSource);
                            }
                            if (!revision().equals(operation.baseRevision()))
                                throw conflict("pluginGraphChanged");
                            String actor =
                                    jdbc.sql(
                                                    "SELECT username FROM platform_user WHERE id=:id AND enabled AND deleted_at IS NULL")
                                            .param("id", operation.decision().actorId())
                                            .query(String.class)
                                            .optional()
                                            .orElseThrow(() -> conflict("forbidden"));
                            requireLifecyclePermissions(actor, plan);
                            if (plan.kind().equals("UNINSTALL")) {
                                if (!graph().uninstall(plan.moduleId()).equals(plan.remove()))
                                    throw conflict("pluginGraphChanged");
                                requireNoConfiguredDependencies(plan.moduleId());
                                for (String id : plan.remove()) {
                                    persistInstalled(id, false, false);
                                    setFlag(id, false);
                                }
                            } else {
                                for (String id : plan.install()) {
                                    persistInstalled(id, true, id.equals(plan.moduleId()));
                                    setFlag(id, true);
                                }
                                for (String id : plan.enable()) setFlag(id, true);
                                persistInstalled(plan.moduleId(), true, true);
                            }
                            String actual = revision();
                            if (!actual.equals(operation.decision().targetRevision()))
                                throw conflict("pluginGraphChanged");
                            audit.append(
                                    actor,
                                    plan.kind().equals("INSTALL")
                                            ? "plugin.installed"
                                            : "plugin.uninstalled",
                                    operation.id());
                            int updated =
                                    jdbc.sql(
                                                    "UPDATE platform_plugin_graph_approval SET graph_revision=:revision,approved_at=now(),approved_by=:actor WHERE id=1")
                                            .param("revision", actual)
                                            .param("actor", actor)
                                            .update();
                            if (updated != 1)
                                throw new IllegalStateException("pluginGraphApprovalUnavailable");
                            return null;
                        }
                    });
        }

        @Override
        public void apply(ClusterOperationJournal.Operation operation) throws Exception {
            reconcileDecision(operation, operation.decision().targetRevision());
        }

        @Override
        public void abort(ClusterOperationJournal.Operation operation) throws Exception {
            reconcileDecision(operation, operation.baseRevision());
        }

        private void reconcileDecision(
                ClusterOperationJournal.Operation operation, String expectedRevision)
                throws Exception {
            synchronized (lock) {
                if (!revision().equals(expectedRevision)) throw conflict("pluginGraphChanged");
                if (clusterColdRecovery || !ready) {
                    clusterColdRecovery = true;
                    beginTrafficTransition(clusterAffected(operation));
                    allowTransitionApproval(operation.decision().targetRevision());
                    busy = true;
                    reconcileClusterGraph(operation, true);
                } else
                    for (String id : clusterAffected(operation)) {
                        if (installed(id) && storedEnabled(id)) {
                            load(id);
                            var handle = handles.get(id);
                            if (handle != null) handle.generation = storedGeneration(id);
                        } else unload(id);
                    }
                registerCatalog();
            }
        }

        @Override
        public void aborted(ClusterOperationJournal.Operation operation) {
            String actor =
                    jdbc.sql("SELECT username FROM platform_user WHERE id=:id")
                            .param("id", operation.decision().actorId())
                            .query(String.class)
                            .optional()
                            .orElse(operation.decision().actorId().toString());
            audit.append(
                    actor,
                    operation.decision().kind() == ClusterOperationJournal.Kind.INSTALL
                            ? "plugin.installFailed"
                            : "plugin.uninstallFailed",
                    operation.id());
        }

        @Override
        public void finish(ClusterOperationJournal.Operation operation) throws Exception {
            synchronized (lock) {
                if (operation.phase() != ClusterOperationJournal.Phase.COMPLETED
                        && operation.phase() != ClusterOperationJournal.Phase.ABORTED)
                    throw new IllegalStateException("clusterDecisionNotTerminal");
                String expected =
                        operation.phase() == ClusterOperationJournal.Phase.COMPLETED
                                ? operation.decision().targetRevision()
                                : operation.baseRevision();
                if (!revision().equals(expected)) {
                    var snapshot = graphSnapshot();
                    if (clusterJournal.blocksGraphChange()
                            || !snapshot.revision().equals(snapshot.approvedRevision()))
                        throw conflict("pluginGraphChanged");
                    // This node missed a successor while its own lease was expired. Rebuild only
                    // from the current approved graph, never publish the older terminal snapshot.
                    ready = false;
                    for (String id : List.copyOf(handles.keySet())) unload(id);
                    artifacts.clear();
                    reconcileClusterGraph(operation, false);
                    expected = snapshot.revision();
                }
                for (String id : clusterAffected(operation)) {
                    var handle = handles.get(id);
                    if (handle != null) handle.generation = storedGeneration(id);
                    resumeAccepting(id);
                }
                registerCatalog();
                boolean coldRecovery = clusterColdRecovery;
                if (!coldRecovery) publishCommittedGraph(expected);
                clusterColdRecovery = false;
                trafficTransition = null;
                busy = false;
                if (!coldRecovery) updateNodeState(true, expected);
            }
        }
    }

    /** Artifact initialization is read-only with respect to the shared graph and its approval. */
    private void hydrateClusterArtifacts(
            ClusterOperationJournal.Operation operation, boolean includeDecision) throws Exception {
        artifactStore.initialize();
        var wanted = new TreeMap<String, String>();
        for (var installation : installationMap().values())
            if (installation.installed())
                wanted.put(installation.moduleId(), installation.digest());
        if (includeDecision)
            for (var item : operation.decision().digests().entrySet()) {
                String installedDigest = wanted.get(item.getKey());
                if (installedDigest != null && !installedDigest.equals(item.getValue()))
                    throw conflict("pluginGraphChanged");
                wanted.putIfAbsent(item.getKey(), item.getValue());
            }
        for (var item : wanted.entrySet()) {
            var existing = artifacts.get(item.getKey());
            if (existing != null && existing.digest().equals(item.getValue())) continue;
            String json =
                    jdbc.sql(
                                    "SELECT manifest::text FROM platform_plugin_artifact WHERE module_id=:id AND digest=:digest")
                            .param("id", item.getKey())
                            .param("digest", item.getValue())
                            .query(String.class)
                            .single();
            var manifest = mapper.readValue(json, PluginManifest.class);
            artifacts.put(item.getKey(), artifactStore.stored(item.getValue(), manifest));
        }
        registerCatalog();
    }

    /** Cold recovery must load every enabled installed module before admitting any traffic. */
    private void reconcileClusterGraph(
            ClusterOperationJournal.Operation operation, boolean includeDecision) throws Exception {
        hydrateClusterArtifacts(operation, includeDecision);
        for (String id : List.copyOf(handles.keySet()))
            if (!installed(id) || !storedEnabled(id)) unload(id);
        for (String id : orderAll()) if (installed(id) && storedEnabled(id)) load(id);
        registerCatalog();
    }

    private void step(UUID id, String status, String step, String error) {
        int updated =
                jdbc.sql(
                                "UPDATE platform_plugin_operation SET status=:status,step=:step,error_code=:error,lease_expires_at=CASE WHEN :status IN ('SUCCEEDED','FAILED','CANCELLED') THEN NULL ELSE clock_timestamp()+interval '45 seconds' END,updated_at=clock_timestamp() WHERE id=:id AND owner_node_id=:owner AND status IN ('QUEUED','RUNNING') AND lease_expires_at>clock_timestamp()")
                        .param("id", id)
                        .param("owner", nodeId)
                        .param("status", status)
                        .param("step", step)
                        .param("error", error)
                        .update();
        if (updated != 1) throw conflict("pluginOperationLeaseLost");
    }

    private void runOperation(String actor, UUID op, Plan plan) {
        var added = new ArrayList<String>();
        var enabled = new ArrayList<String>();
        var removed = new ArrayList<String>();
        var lifecycleCommitted = new AtomicReference<>(false);
        var recoveryFailed = new AtomicReference<>(false);
        try {
            withClusterLifecycleLock(
                    () -> {
                        synchronized (lock) {
                            requireOperationTopologyAllowed();
                            requireLifecyclePermissions(actor, plan);
                            if (!makePlan(plan.moduleId(), plan.kind(), plan.dataMode())
                                    .revision()
                                    .equals(plan.revision())) throw conflict("staleRecord");
                            var affected = new HashSet<>(plan.install());
                            affected.addAll(plan.enable());
                            affected.addAll(plan.remove());
                            affected.add(plan.moduleId());
                            beginTrafficTransition(affected);
                            if (plan.kind().equals("INSTALL")) {
                                for (String id : plan.install()) {
                                    step(op, "RUNNING", "install " + id, null);
                                    load(id, msg -> step(op, "RUNNING", msg, null));
                                    added.add(id);
                                }
                                for (String id : plan.enable()) {
                                    step(op, "RUNNING", "enable " + id, null);
                                    load(id);
                                    enabled.add(id);
                                }
                                String completedRevision =
                                        commitLifecycleOperation(actor, op, plan);
                                lifecycleCommitted.set(true);
                                publishCommittedGraph(completedRevision);
                            } else {
                                // Drain all affected handles before removing any provider.
                                for (String id : plan.remove()) drain(id);
                                if (!makePlan(plan.moduleId(), plan.kind(), plan.dataMode())
                                        .revision()
                                        .equals(plan.revision())) throw conflict("staleRecord");
                                boolean deleteUninstall = plan.dataMode().equals("DELETE");
                                for (String id : plan.remove()) {
                                    step(op, "RUNNING", "unload " + id, null);
                                    boolean wasLoaded = handles.containsKey(id);
                                    try {
                                        unload(id);
                                    } catch (Exception unloadError) {
                                        if (wasLoaded && !handles.containsKey(id)) removed.add(id);
                                        throw unloadError;
                                    }
                                    if (wasLoaded) removed.add(id);
                                }
                                if (deleteUninstall) {
                                    step(op, "RUNNING", "delete data " + plan.moduleId(), null);
                                }
                                String completedRevision =
                                        commitLifecycleOperation(actor, op, plan);
                                lifecycleCommitted.set(true);
                                publishCommittedGraph(completedRevision);
                            }
                        }
                        return null;
                    });
        } catch (Throwable error) {
            // A committed graph is authoritative even if local publication subsequently fails.
            // Never undo it as though preparation had failed.
            if (lifecycleCommitted.get()) {
                recoveryFailed.set(true);
                System.getLogger(getClass().getName())
                        .log(
                                System.Logger.Level.ERROR,
                                "Plugin graph committed but local publication failed: " + op,
                                error);
                return;
            }
            boolean restoreRemovedResources = plan.kind().equals("UNINSTALL");
            try {
                recoverFailedOperation(
                        op,
                        added,
                        enabled,
                        removed,
                        restoreRemovedResources,
                        recoveryFailed,
                        error);
            } catch (Throwable recoveryError) {
                recoveryFailed.set(true);
                error.addSuppressed(recoveryError);
                System.getLogger(getClass().getName())
                        .log(
                                System.Logger.Level.ERROR,
                                "Plugin runtime recovery failed after lifecycle operation " + op,
                                recoveryError);
            }
            String failedStep =
                    jdbc.sql("SELECT step FROM platform_plugin_operation WHERE id=:id")
                            .param("id", op)
                            .query(String.class)
                            .single();
            String code =
                    failedStep.startsWith("delete data ")
                            ? "pluginDataDeletionFailed"
                            : error instanceof ResponseStatusException r
                                    ? r.getReason()
                                    : error instanceof IllegalArgumentException
                                            ? error.getMessage()
                                            : "pluginStartFailed";
            try {
                tx.executeWithoutResult(
                        status ->
                                audit.append(
                                        actor,
                                        plan.kind().equals("INSTALL")
                                                ? "plugin.installFailed"
                                                : "plugin.uninstallFailed",
                                        op));
            } catch (Exception auditError) {
                System.getLogger(getClass().getName())
                        .log(
                                System.Logger.Level.WARNING,
                                "Plugin failure audit unavailable",
                                auditError);
            }
            try {
                step(op, "FAILED", failedStep + " failed", code);
            } catch (RuntimeException leaseLost) {
                System.getLogger(getClass().getName())
                        .log(
                                System.Logger.Level.WARNING,
                                "Plugin operation lease was lost",
                                leaseLost);
            }
            System.getLogger(getClass().getName())
                    .log(System.Logger.Level.ERROR, "Plugin operation failed: " + op, error);
        } finally {
            try {
                String currentRevision = revision();
                String approvedRevision =
                        jdbc.sql(
                                        "SELECT graph_revision FROM platform_plugin_graph_approval WHERE id=1")
                                .query(String.class)
                                .optional()
                                .orElse("");
                ready =
                        !recoveryFailed.get()
                                && currentRevision.equals(loadedGraphRevision)
                                && currentRevision.equals(approvedRevision)
                                && heartbeatHealthy;
                updateNodeState(ready, loadedGraphRevision);
            } catch (RuntimeException failure) {
                ready = false;
                updateNodeState(false, loadedGraphRevision);
            } finally {
                trafficTransition = null;
                busy = false;
            }
        }
    }

    private void recoverFailedOperation(
            UUID op,
            List<String> added,
            List<String> enabled,
            List<String> removed,
            boolean restoreRemovedResources,
            AtomicReference<Boolean> recoveryFailed,
            Throwable operationError)
            throws Exception {
        withClusterLifecycleLock(
                () -> {
                    synchronized (lock) {
                        Collections.reverse(added);
                        for (String id : added) {
                            try {
                                unload(id);
                            } catch (Exception ignored) {
                            }
                        }
                        for (String id : enabled) {
                            try {
                                unload(id);
                            } catch (Exception ignored) {
                            }
                        }
                        if (restoreRemovedResources) {
                            // A peer may have changed the graph after the failed transaction
                            // released its lifecycle lock. Only reload the old graph if it is
                            // still the sole approved graph in this runtime.
                            requireOperationTopologyAllowed();
                            Collections.reverse(removed);
                            for (String id : removed) {
                                try {
                                    load(id);
                                } catch (Throwable restorationError) {
                                    recoveryFailed.set(true);
                                    operationError.addSuppressed(restorationError);
                                    System.getLogger(getClass().getName())
                                            .log(
                                                    System.Logger.Level.ERROR,
                                                    "Plugin runtime restoration failed after uninstall operation "
                                                            + op
                                                            + " for "
                                                            + id,
                                                    restorationError);
                                }
                            }
                        }
                        try {
                            registerCatalog();
                        } catch (RuntimeException catalogError) {
                            if (restoreRemovedResources) {
                                recoveryFailed.set(true);
                                operationError.addSuppressed(catalogError);
                            }
                            System.getLogger(getClass().getName())
                                    .log(
                                            System.Logger.Level.WARNING,
                                            "Plugin permission catalog refresh failed",
                                            catalogError);
                        }
                        // Failed draining restores admission on all still-published handles.
                        handles.values()
                                .forEach(
                                        h -> {
                                            synchronized (h) {
                                                h.accepting = true;
                                            }
                                        });
                    }
                    return null;
                });
    }

    /**
     * Publish one durable graph: installation, flags, catalog, audit and operation commit together.
     */
    private String commitLifecycleOperation(String actor, UUID op, Plan plan) {
        return tx.execute(
                status -> {
                    var affected = new HashSet<>(plan.install());
                    affected.addAll(plan.enable());
                    affected.addAll(plan.remove());
                    affected.add(plan.moduleId());
                    fenceLifecycle(affected);
                    // Lock the owner token before any mutation. A heartbeat cannot revive an
                    // expired token, and completion checks wall time again after all work.
                    var owned =
                            jdbc.sql(
                                            "SELECT id FROM platform_plugin_operation WHERE id=:id AND owner_node_id=:owner AND status IN ('QUEUED','RUNNING') AND lease_expires_at>clock_timestamp() FOR UPDATE")
                                    .param("id", op)
                                    .param("owner", nodeId)
                                    .query(UUID.class)
                                    .optional();
                    if (owned.isEmpty()) throw conflict("pluginOperationLeaseLost");
                    if (plan.kind().equals("INSTALL")) {
                        for (String id : plan.install()) {
                            persistInstalled(id, true, id.equals(plan.moduleId()));
                            setFlag(id, true);
                        }
                        for (String id : plan.enable()) setFlag(id, true);
                        if (installed(plan.moduleId()))
                            persistInstalled(plan.moduleId(), true, true);
                    } else {
                        for (String id : plan.remove()) {
                            persistInstalled(id, false, false);
                            setFlag(id, false);
                        }
                        if (plan.dataMode().equals("DELETE")) {
                            PluginDataRemoval.delete(
                                    jdbc, artifacts.get(plan.moduleId()).manifest().dataSets());
                            audit.append(actor, "plugin.dataDeleted", op);
                        }
                    }
                    audit.append(
                            actor,
                            plan.kind().equals("INSTALL")
                                    ? "plugin.installed"
                                    : "plugin.uninstalled",
                            op);
                    registerCatalog();
                    String completedRevision = revision();
                    allowTransitionApproval(completedRevision);
                    jdbc.sql(
                                    "UPDATE platform_plugin_graph_approval SET graph_revision=:revision,approved_at=now(),approved_by=:actor WHERE id=1")
                            .param("revision", completedRevision)
                            .param("actor", actor)
                            .update();
                    step(op, "SUCCEEDED", "complete", null);
                    return completedRevision;
                });
    }

    private void persistInstalled(String id, boolean value, boolean explicit) {
        var a = artifacts.get(id);
        jdbc.sql(
                        "INSERT INTO platform_plugin_installation(module_id,digest,installed,explicit_install) VALUES(:id,:digest,:installed,:explicit) ON CONFLICT(module_id) DO UPDATE SET digest=EXCLUDED.digest,installed=:installed,explicit_install=:explicit,updated_at=now()")
                .param("id", id)
                .param("digest", a.digest())
                .param("installed", value)
                .param("explicit", explicit)
                .update();
    }

    private void setFlag(String id, boolean value) {
        jdbc.sql(
                        "INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(:uuid,:id,:enabled,1) ON CONFLICT(module_id) DO UPDATE SET enabled=:enabled,version=platform_module_state.version+1,updated_at=now()")
                .param("uuid", UUID.randomUUID())
                .param("id", id)
                .param("enabled", value)
                .update();
    }

    public Operation operation(String actor, UUID id) {
        read(actor);
        if (clusterEnabled && clusterJournal.protocolReady()) {
            var clustered = tx.execute(status -> clusterJournal.find(id));
            if (clustered.isPresent()) return clusterOperation(clustered.get());
        }
        return jdbc.sql(
                        "SELECT id,module_id,kind,status,step,error_code,data_mode FROM platform_plugin_operation WHERE id=:id")
                .param("id", id)
                .query(Operation.class)
                .optional()
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "pluginNotFound"));
    }

    public ModuleStateStore.State change(
            String actor, String id, boolean enabled, long version, String reason) {
        guard.requireGlobal(actor, PlatformPageId.MODULES.accessPermission());
        guard.requireGlobal(actor, PlatformOperation.MODULE_UPDATE.permission());
        String changeReason =
                reason == null || reason.isBlank()
                        ? ModuleStateStore.DEFAULT_CHANGE_REASON
                        : reason.strip();
        if (version < 0 || changeReason.length() > 1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidModuleChange");
        try {
            return withClusterLifecycleLock(
                    () -> {
                        synchronized (lock) {
                            requireLifecycleChangeAllowed();
                            if (activeNodeCount() > 1)
                                throw conflict("pluginClusterLifecycleUnsupported");
                            if (busy) throw conflict("pluginBusy");
                            if (!installed(id)) throw conflict("pluginNotInstalled");
                            var currentStates = states.states();
                            if (currentStates.stream()
                                            .filter(s -> s.moduleId().equals(id))
                                            .findFirst()
                                            .orElseThrow()
                                            .version()
                                    != version) throw conflict("staleRecord");
                            if (!enabled) {
                                var active = new HashSet<String>();
                                currentStates.stream()
                                        .filter(ModuleStateStore.State::enabled)
                                        .forEach(state -> active.add(state.moduleId()));
                                if (registry.modules().stream()
                                        .anyMatch(
                                                module ->
                                                        active.contains(module.id())
                                                                && module.dependencies()
                                                                        .contains(id)))
                                    throw conflict("moduleDependencyConflict");
                            }
                            if (!enabled) requireNoConfiguredDependencies(id);
                            beginTrafficTransition(Set.of(id));
                            busy = true;
                            updateNodeState(true, loadedGraphRevision);
                            boolean previouslyLoaded = handles.containsKey(id);
                            boolean resourceRecoveryFailed = false;
                            try {
                                if (enabled) loadUnchecked(id);
                                else {
                                    drain(id);
                                    requireNoConfiguredDependencies(id);
                                }
                                AtomicReference<String> committedRevision = new AtomicReference<>();
                                var changed =
                                        tx.execute(
                                                status -> {
                                                    fenceLifecycle(Set.of(id));
                                                    var result =
                                                            states.change(
                                                                    id,
                                                                    enabled,
                                                                    version,
                                                                    actor,
                                                                    changeReason);
                                                    if (!enabled) {
                                                        try {
                                                            unload(id);
                                                        } catch (Exception error) {
                                                            throw new IllegalStateException(
                                                                    "pluginShutdownFailed", error);
                                                        }
                                                    }
                                                    committedRevision.set(
                                                            persistGraphApproval(actor));
                                                    return result;
                                                });
                                publishCommittedGraph(committedRevision.get());
                                return changed;
                            } catch (Exception failure) {
                                // Failed durable publication leaves the previous flags and versions
                                // intact. Restore resources, without a compensating state mutation.
                                try {
                                    if (enabled && !previouslyLoaded) unload(id);
                                    else if (!enabled && previouslyLoaded) load(id);
                                    resumeAccepting(id);
                                } catch (Exception recovery) {
                                    failure.addSuppressed(recovery);
                                    resourceRecoveryFailed = true;
                                    ready = false;
                                }
                                throw failure;
                            } finally {
                                try {
                                    String currentRevision = revision();
                                    String approvedRevision =
                                            jdbc.sql(
                                                            "SELECT graph_revision FROM platform_plugin_graph_approval WHERE id=1")
                                                    .query(String.class)
                                                    .optional()
                                                    .orElse("");
                                    ready =
                                            !resourceRecoveryFailed
                                                    && currentRevision.equals(loadedGraphRevision)
                                                    && currentRevision.equals(approvedRevision)
                                                    && heartbeatHealthy;
                                    updateNodeState(ready, loadedGraphRevision);
                                } finally {
                                    trafficTransition = null;
                                    busy = false;
                                }
                            }
                        }
                    });
        } catch (ModuleStateStore.Rejected error) {
            throw new ResponseStatusException(
                    Set.of("staleRecord", "moduleDependencyConflict", "requiredModule")
                                    .contains(error.getMessage())
                            ? HttpStatus.CONFLICT
                            : HttpStatus.BAD_REQUEST,
                    error.getMessage());
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "pluginClusterLifecycleBusy", error);
        }
    }

    private void loadUnchecked(String id) {
        try {
            load(id);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "pluginStartFailed", e);
        }
    }

    private void load(String id) throws Exception {
        load(id, msg -> {});
    }

    private void load(String id, java.util.function.Consumer<String> progress) throws Exception {
        if (handles.containsKey(id)) return;
        PluginFrameworkCaches.validate();
        var a = artifacts.get(id);
        Path jar = root.resolve("loaded").resolve(id + ".jar");
        Files.copy(
                a.directory().resolve(a.manifest().backend()),
                jar,
                StandardCopyOption.REPLACE_EXISTING);
        var mappings = new ArrayList<Published>();
        var published = new ArrayList<Published>();
        var context = new AnnotationConfigApplicationContext();
        var taskHandle = new java.util.concurrent.atomic.AtomicReference<Handle>();
        var resources = new PluginOwnedResources(id, () -> acquireOwnedWork(taskHandle.get()));
        try {
            manager.loadPlugin(jar);
            var wrapper = manager.getPlugin(id);
            if (!(wrapper.getPlugin() instanceof PluginEntry entry))
                throw new IllegalArgumentException("pluginEntryIncompatible");
            context.setParent(host);
            context.setClassLoader(wrapper.getPluginClassLoader());
            context.registerBean(com.lumira.plugin.sdk.PluginResources.class, () -> resources);
            registerDependencyServices(a.manifest(), context);
            progress.accept("create container " + id);
            context.register(entry.configuration());
            context.refresh();
            validateOwnedServices(
                    id,
                    context
                            .getBeansOfType(com.lumira.platform.common.PluginServicePort.class)
                            .entrySet()
                            .stream()
                            .filter(bean -> !bean.getKey().startsWith("__lumiraDependency."))
                            .map(Map.Entry::getValue)
                            .toList());
            if (!context.getBeansOfType(
                            org.springframework.scheduling.annotation
                                    .ScheduledAnnotationBeanPostProcessor.class)
                    .isEmpty()) throw new IllegalArgumentException("pluginUnmanagedTask");
            var mapping = mappingProvider.getObject();
            for (String beanName : context.getBeanNamesForAnnotation(RestController.class)) {
                var bean = context.getBean(beanName);
                var type = org.springframework.aop.support.AopUtils.getTargetClass(bean);
                var owner = AnnotatedElementUtils.findMergedAnnotation(type, ModuleEndpoint.class);
                if (owner == null || !owner.value().equals(id))
                    throw new IllegalArgumentException("pluginEndpointOwnerMismatch");
                for (var method : type.getMethods()) {
                    var info = PluginHttpMappings.mapping(type, method, mapping);
                    if (info == null) continue;
                    if (info.getMethodsCondition().getMethods().isEmpty())
                        throw new IllegalArgumentException("pluginEndpointPermissionMissing");
                    for (String path : info.getPatternValues())
                        for (var verb : info.getMethodsCondition().getMethods())
                            if (!covered(a.manifest(), verb.name(), path))
                                throw new IllegalArgumentException(
                                        "pluginEndpointPermissionMissing:" + path);
                    mappings.add(new Published(info, bean, method));
                }
            }
            validateNavigation(a.manifest());
            for (var p : mappings)
                if (mapping.getHandlerMethods().containsKey(p.mapping()))
                    throw conflict("pluginEndpointConflict");
            // Apply only forward migrations, with a distinct table per plugin.
            progress.accept("migrate " + id);
            Path migrations = a.directory().resolve("migrations");
            if (Files.isDirectory(migrations))
                PluginPermissionMigrationRunner.migrate(
                        host.getBean(DataSource.class), id, migrations);
            progress.accept("health check " + id);
            entry.checkHealth();
            manager.startPlugin(id);
            progress.accept("publish " + id);
            for (var p : mappings) {
                mapping.registerMapping(p.mapping(), p.bean(), p.method());
                published.add(p);
            }
            handles.put(id, new Handle(id, context, List.copyOf(mappings), resources));
            registry.live(id, true);
            var publishedHandle = handles.get(id);
            publishedHandle.digest = a.digest();
            publishedHandle.generation = storedGeneration(id);
            taskHandle.set(publishedHandle);
            var producers = context.getBeansOfType(DurableEventProducerPort.class).values();
            if (!producers.isEmpty())
                resources.ownRegistration(
                        "platform-event-producers",
                        host.getBean(DynamicEventConsumerPort.class)
                                .registerProducers(id, producers));
            var consumers = context.getBeansOfType(DurableEventConsumerPort.class).values();
            if (!consumers.isEmpty()) {
                resources.ownRegistration(
                        "platform-event-consumers",
                        host.getBean(DynamicEventConsumerPort.class)
                                .registerConsumers(
                                        id,
                                        consumers,
                                        () -> {
                                            if (!isReadyForTraffic() || !isModuleEnabled(id))
                                                return null;
                                            try {
                                                return acquireOwnedWork(publishedHandle);
                                            } catch (ResponseStatusException unavailable) {
                                                return null;
                                            }
                                        }));
            }
            registerNavigation(a.manifest());
            resources.start();
        } catch (Throwable e) {
            for (var p : published) mappingProvider.getObject().unregisterMapping(p.mapping());
            resources.close();
            context.close();
            try {
                manager.stopPlugin(id);
                manager.unloadPlugin(id);
            } catch (Exception ignored) {
            }
            if (context.getClassLoader() instanceof PluginClassLoader)
                PluginFrameworkCaches.release(host, context.getClassLoader());
            registry.live(id, false);
            handles.remove(id);
            throw e;
        }
    }

    private void registerDependencyServices(
            PluginManifest manifest, AnnotationConfigApplicationContext context) {
        var contracts = new HashSet<Class<?>>();
        for (var dependency : manifest.dependencies()) {
            var provider = handles.get(dependency.id());
            if (provider == null) continue; // Kernel contracts come from the parent context.
            for (var service :
                    provider.context.getBeansOfType(PluginServicePort.class).entrySet().stream()
                            .filter(bean -> !bean.getKey().startsWith("__lumiraDependency."))
                            .map(Map.Entry::getValue)
                            .toList()) {
                if (!dependency.id().equals(service.ownerPluginId())) continue;
                for (var contract :
                        org.springframework.util.ClassUtils.getAllInterfacesForClass(
                                service.getClass())) {
                    if (contract == PluginServicePort.class
                            || !PluginServicePort.class.isAssignableFrom(contract)) continue;
                    try {
                        if (host.getClassLoader().loadClass(contract.getName()) != contract)
                            throw new IllegalArgumentException("pluginServiceContractNotShared");
                    } catch (ClassNotFoundException error) {
                        throw new IllegalArgumentException("pluginServiceContractNotShared", error);
                    }
                    if (!contracts.add(contract))
                        throw new IllegalArgumentException("pluginServiceContractConflict");
                    String owner = dependency.id();
                    Object proxy =
                            java.lang.reflect.Proxy.newProxyInstance(
                                    contract.getClassLoader(),
                                    new Class<?>[] {contract},
                                    (instance, method, arguments) -> {
                                        if (method.getDeclaringClass() == Object.class) {
                                            return switch (method.getName()) {
                                                case "toString" ->
                                                        "Plugin dependency "
                                                                + owner
                                                                + ":"
                                                                + contract.getName();
                                                case "hashCode" ->
                                                        System.identityHashCode(instance);
                                                case "equals" -> instance == arguments[0];
                                                default ->
                                                        throw new IllegalStateException(
                                                                method.getName());
                                            };
                                        }
                                        return withServiceLease(
                                                owner,
                                                contract,
                                                target -> {
                                                    try {
                                                        return method.invoke(target, arguments);
                                                    } catch (
                                                            java.lang.reflect
                                                                            .InvocationTargetException
                                                                    error) {
                                                        throw error.getCause();
                                                    }
                                                });
                                    });
                    registerDependencyProxy(
                            context,
                            "__lumiraDependency." + owner + "." + contract.getName(),
                            contract,
                            proxy);
                }
            }
        }
    }

    private static <T> void registerDependencyProxy(
            AnnotationConfigApplicationContext context,
            String name,
            Class<T> contract,
            Object proxy) {
        context.registerBean(
                name,
                contract,
                () -> contract.cast(proxy),
                definition -> {
                    definition.setPrimary(true);
                    definition.setDestroyMethodName("");
                });
    }

    @FunctionalInterface
    private interface ServiceCall<T, R> {
        R call(T service) throws Throwable;
    }

    /** Both dependency proxies and kernel adapters use the same transaction-aware drain lease. */
    private <T, R> R withServiceLease(String owner, Class<T> contract, ServiceCall<T, R> call)
            throws Throwable {
        var current = handles.get(owner);
        var lease = acquire(current);
        boolean deferred = false;
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()
                    && TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int status) {
                                try {
                                    lease.close();
                                } catch (Exception error) {
                                    throw new IllegalStateException(error);
                                }
                            }
                        });
                deferred = true;
            }
            var targets =
                    current.context.getBeansOfType(contract).entrySet().stream()
                            .filter(bean -> !bean.getKey().startsWith("__lumiraDependency."))
                            .map(Map.Entry::getValue)
                            .filter(
                                    bean ->
                                            bean instanceof PluginServicePort owned
                                                    && owner.equals(owned.ownerPluginId()))
                            .toList();
            if (targets.size() != 1)
                throw new IllegalStateException("pluginServiceContractConflict");
            try (var scope = invocationScope(current)) {
                return call.call(targets.getFirst());
            }
        } finally {
            if (!deferred) lease.close();
        }
    }

    @Override
    public <T extends PluginServicePort, R> R service(
            String owner, Class<T> contract, java.util.function.Function<T, R> call) {
        if (owner == null
                || !owner.matches("[a-z][a-z0-9-]{0,63}")
                || contract == null
                || !contract.isInterface()
                || contract == PluginServicePort.class
                || !PluginServicePort.class.isAssignableFrom(contract)
                || call == null)
            throw new IllegalArgumentException("pluginServiceContractNotShared");
        try {
            if (host.getClassLoader().loadClass(contract.getName()) != contract)
                throw new IllegalArgumentException("pluginServiceContractNotShared");
        } catch (ClassNotFoundException error) {
            throw new IllegalArgumentException("pluginServiceContractNotShared", error);
        }
        if (!isReadyForTraffic() || !isModuleEnabled(owner))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "moduleUnavailable");
        try {
            return withServiceLease(owner, contract, call::apply);
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("pluginServiceInvocationFailed", error);
        }
    }

    private void resumeAccepting(String id) {
        var handle = handles.get(id);
        if (handle != null)
            synchronized (handle) {
                handle.accepting = true;
            }
    }

    private List<String> configuredBlockers(String id) {
        return configuredDependencies
                .orderedStream()
                .flatMap(provider -> provider.blockers(id).stream())
                .distinct()
                .sorted()
                .toList();
    }

    private void requireNoConfiguredDependencies(String id) {
        if (!configuredBlockers(id).isEmpty())
            throw new ModuleStateStore.Rejected("moduleDependencyConflict");
    }

    static void validateOwnedServices(
            String owner, Collection<com.lumira.platform.common.PluginServicePort> services) {
        var types = new HashSet<String>();
        for (var service : services) {
            if (!owner.equals(service.ownerPluginId())
                    || service.serviceType() == null
                    || !service.serviceType().matches("[a-z][a-z0-9-]{0,63}")
                    || !types.add(service.serviceType()))
                throw new IllegalArgumentException("pluginServiceOwnerConflict");
        }
    }

    private static Set<String> canonicalPatterns(RequestMappingInfo mapping) {
        return mapping.getPatternValues().stream()
                .map(p -> p.replaceAll("\\{[^}]+\\}", "{}"))
                .collect(java.util.stream.Collectors.toSet());
    }

    private void validateEndpoints(PluginArchive.Artifact artifact) {
        try {
            var hostMappings = mappingProvider.getObject();
            for (var candidate : PluginArchive.mappings(artifact, hostMappings))
                for (var existing : hostMappings.getHandlerMethods().entrySet()) {
                    var owner =
                            AnnotatedElementUtils.findMergedAnnotation(
                                    org.springframework.aop.support.AopUtils.getTargetClass(
                                            existing.getValue().getBean() instanceof String name
                                                    ? host.getBean(name)
                                                    : existing.getValue().getBean()),
                                    ModuleEndpoint.class);
                    if (owner != null && owner.value().equals(artifact.manifest().id())) continue;
                    if (!Collections.disjoint(
                                    canonicalPatterns(candidate),
                                    canonicalPatterns(existing.getKey()))
                            && (existing.getKey().getMethodsCondition().getMethods().isEmpty()
                                    || !Collections.disjoint(
                                            candidate.getMethodsCondition().getMethods(),
                                            existing.getKey().getMethodsCondition().getMethods())))
                        throw new IllegalArgumentException("pluginEndpointConflict");
                }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("invalidPluginPackage", e);
        }
    }

    private PluginHttpRuleIndex rulesFor(PluginArchive.Artifact artifact) {
        var indexed = routeIndexes.get(artifact.manifest().id());
        if (indexed != null && indexed.artifact() == artifact) return indexed.rules();
        return routeIndexes
                .compute(
                        artifact.manifest().id(),
                        (id, current) ->
                                current != null && current.artifact() == artifact
                                        ? current
                                        : new IndexedArtifact(
                                                artifact,
                                                new PluginHttpRuleIndex(
                                                        artifact.manifest().apiRules())))
                .rules();
    }

    private boolean covered(PluginManifest m, String method, String path) {
        return new PluginHttpRuleIndex(m.apiRules())
                        .first(method, PluginHttpRuleIndex.requestPath(path))
                != null;
    }

    static List<String> bootstrapPluginIds(Path source, ObjectMapper mapper)
            throws java.io.IOException {
        var ids = Arrays.asList(mapper.readValue(Files.readString(source), String[].class));
        if (ids.size() > 100
                || new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(id -> id == null || !id.matches("[a-z][a-z0-9-]{0,63}")))
            throw new IllegalArgumentException("invalidPluginBootstrapManifest");
        return List.copyOf(ids);
    }

    private void validateNavigation(PluginManifest m) {
        for (var r : m.routes()) {
            var owner =
                    jdbc.sql(
                                    "SELECT COALESCE(module_id,'') FROM platform_navigation_menu WHERE section_key=:id AND NOT deleted")
                            .param("id", r.id())
                            .query(String.class)
                            .optional();
            if (owner.isPresent()
                    && !m.id().equals(owner.get())
                    && !(legacyNavigationOwner(m, owner.get()) && legacyNavigation(m, r, false)))
                throw new IllegalArgumentException("pluginRouteConflict:" + r.id());
            for (var other : artifacts.values())
                if (!other.manifest().id().equals(m.id()))
                    for (var route : other.manifest().routes())
                        if (route.id().equals(r.id()))
                            throw new IllegalArgumentException("pluginRouteConflict:" + r.id());
        }
    }

    private void registerNavigation(PluginManifest m) {
        for (var r : m.routes()) {
            var existing =
                    jdbc.sql(
                                    "SELECT COALESCE(module_id,'') FROM platform_navigation_menu WHERE section_key=:id AND NOT deleted")
                            .param("id", r.id())
                            .query(String.class)
                            .optional();
            if (existing.isPresent()) {
                if (!m.id().equals(existing.get())
                        && !(legacyNavigationOwner(m, existing.get())
                                && legacyNavigation(m, r, true)))
                    throw conflict("pluginRouteConflict");
                continue;
            }
            UUID parent =
                    jdbc.sql(
                                    "SELECT id FROM platform_navigation_menu WHERE menu_code=:group AND menu_type='CATALOG' AND NOT deleted")
                            .param("group", r.group())
                            .query(UUID.class)
                            .optional()
                            .orElse(null);
            jdbc.sql(
                            "INSERT INTO platform_navigation_menu(id,parent_id,menu_code,label_key,label_default,menu_type,section_key,path,component_key,permission_key,global_only,module_id,sort_order,enabled) VALUES(:uuid,:parent,:id,:label,:label,'PAGE',:id,:path,:id,:permission,:global,:module,:sort,true)")
                    .param("uuid", UUID.randomUUID())
                    .param("parent", parent)
                    .param("id", r.id())
                    .param("label", r.label())
                    .param("path", "/dashboard/" + r.id())
                    .param("permission", r.permission())
                    .param("global", r.global())
                    .param("module", m.id())
                    .param("sort", r.order())
                    .update();
        }
    }

    private static boolean legacyNavigationOwner(PluginManifest manifest, String owner) {
        return owner.isEmpty() || "ai".equals(manifest.id()) && "platform".equals(owner);
    }

    /** Adopt only pinned B52 built-in rows; preserve labels, layout, state and identity. */
    private boolean legacyNavigation(
            PluginManifest manifest, PluginManifest.Route route, boolean adopt) {
        String knownId =
                switch (manifest.id() + ":" + route.id()) {
                    case "delivery-console:mail-deliveries" ->
                            "00000000-0000-0000-0000-000000000034";
                    case "delivery-console:sms-deliveries" ->
                            "00000000-0000-0000-0000-000000000048";
                    case "authentication-console:security" ->
                            "00000000-0000-0000-0000-000000000049";
                    case "authentication-console:authentication" ->
                            "f15212df-d192-491d-a0c6-1c1cbf10171d";
                    case "authentication-console:passkey" -> "00000000-0000-0000-0000-000000000052";
                    case "authentication-console:wechat" -> "ca740762-2005-4b8d-8a4a-87af93a096ce";
                    case "authentication-console:captcha" -> "0e348dcc-ac99-4f40-a844-e14486e77663";
                    case "authentication-console:registration" ->
                            "230e98d2-91d3-4793-b9a8-98f4d96ca209";
                    case "personal-files:files" -> "00000000-0000-0000-0000-000000000011";
                    case "profile-console:profile-fields" -> "00000000-0000-0000-0000-000000000029";
                    case "delivery-settings:smtp" -> "00000000-0000-0000-0000-000000000028";
                    case "delivery-settings:sms" -> "00000000-0000-0000-0000-000000000047";
                    case "site-console:branding" -> "00000000-0000-0000-0000-000000000027";
                    case "site-console:watermark" -> "00000000-0000-0000-0000-000000000031";
                    case "site-console:agreements" -> "00000000-0000-0000-0000-000000000030";
                    case "site-console:maintenance" -> "00000000-0000-0000-0000-000000000032";
                    case "file-console:files-all" -> "00000000-0000-0000-0000-000000000050";
                    case "ai:ai-console" -> "00000000-0000-0000-0000-000000000057";
                    case "ai:ai-models" -> "00000000-0000-0000-0000-000000000058";
                    case "directory:users" -> "00000000-0000-0000-0000-000000000022";
                    case "directory:online-users" -> "00000000-0000-0000-0000-000000000023";
                    case "directory:roles" -> "00000000-0000-0000-0000-000000000024";
                    case "directory:organizations" -> "00000000-0000-0000-0000-000000000025";
                    case "directory:print-templates" -> "00000000-0000-0000-0000-000000000064";
                    case "dictionaries:dictionaries" -> "00000000-0000-0000-0000-000000000026";
                    case "navigation-console:navigation" -> "00000000-0000-0000-0000-000000000037";
                    case "event-console:event-outbox" -> "00000000-0000-0000-0000-000000000035";
                    case "notifications:notifications" -> "00000000-0000-0000-0000-000000000051";
                    case "monitoring:monitoring" -> "00000000-0000-0000-0000-000000000021";
                    default -> null;
                };
        if (knownId == null
                || !Objects.equals(
                        route.permission(),
                        "personal-files".equals(manifest.id())
                                ? null
                                : "platform:" + route.id() + ":access")) return false;
        String predicate =
                " WHERE id=:id AND section_key=:section AND path=:path AND permission_key IS NOT DISTINCT FROM :permission"
                        + " AND COALESCE(module_id,'')=:legacyOwner AND menu_type='PAGE' AND built_in AND global_only=:global AND NOT deleted";
        var query =
                jdbc.sql(
                                (adopt
                                                ? "UPDATE platform_navigation_menu SET module_id=:owner,version=version+1,updated_at=now()"
                                                : "SELECT count(*) FROM platform_navigation_menu")
                                        + predicate)
                        .param("id", UUID.fromString(knownId))
                        .param("legacyOwner", "ai".equals(manifest.id()) ? "platform" : "")
                        .param("global", route.global())
                        .param("section", route.id())
                        .param("path", "/dashboard/" + route.id())
                        .param("permission", route.permission());
        return adopt
                ? query.param("owner", manifest.id()).update() == 1
                : query.query(Integer.class).single() == 1;
    }

    private void drain(String id) throws Exception {
        var h = handles.get(id);
        if (h == null) return;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        synchronized (h) {
            h.accepting = false;
            while (h.calls > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    h.accepting = true;
                    throw conflict("pluginDrainTimeout");
                }
                TimeUnit.NANOSECONDS.timedWait(h, left);
            }
        }
    }

    private void unload(String id) throws Exception {
        drain(id);
        var h = handles.remove(id);
        registry.live(id, false);
        if (h == null) return;
        h.resources.close();
        for (var p : h.mappings) mappingProvider.getObject().unregisterMapping(p.mapping());
        h.context.close();
        manager.stopPlugin(id);
        manager.unloadPlugin(id);
        PluginFrameworkCaches.release(host, h.context.getClassLoader());
    }

    public AutoCloseable acquire(String id) {
        return acquire(handles.get(id));
    }

    PluginInvocationContext.Owner invocationOwner(String id) {
        var handle = handles.get(id);
        if (handle == null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "moduleUnavailable");
        return new PluginInvocationContext.Owner(id, handle.digest, handle.generation, nodeId);
    }

    private PluginInvocationContext invocationScope(Handle handle) {
        return PluginInvocationContext.open(
                new PluginInvocationContext.Owner(
                        handle.owner, handle.digest, handle.generation, nodeId));
    }

    private long storedGeneration(String id) {
        return jdbc.sql("SELECT version FROM platform_module_state WHERE module_id=:id")
                .param("id", id)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    private void fenceLifecycle(Set<String> affected) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("pluginLifecycleFenceRequiresTransaction");
        var connection =
                org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        try {
            PluginTransactionFence.exclusive(connection, affected);
        } catch (java.sql.SQLException busy) {
            throw conflict("pluginDrainTimeout");
        } finally {
            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                    connection, dataSource);
        }
    }

    private AutoCloseable acquireOwnedWork(Handle handle) {
        if (handle == null || !isReadyForTraffic() || !isModuleEnabled(handle.owner))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "moduleUnavailable");
        var lease = acquire(handle);
        var scope = invocationScope(handle);
        return () -> {
            try {
                scope.close();
            } finally {
                lease.close();
            }
        };
    }

    private AutoCloseable acquire(Handle h) {
        if (h == null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "moduleUnavailable");
        synchronized (h) {
            if (!h.accepting || changing(h.owner))
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "moduleUnavailable");
            h.calls++;
        }
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (!released.compareAndSet(false, true)) return;
            synchronized (h) {
                h.calls--;
                h.notifyAll();
            }
        };
    }

    public <T, R> List<R> extensions(Class<T> type, java.util.function.Function<T, R> call) {
        if (!isReadyForTraffic()) return List.of();
        var out = new ArrayList<R>();
        for (var entry : handles.entrySet()) {
            AutoCloseable lease = null;
            boolean deferred = false;
            try {
                lease = acquire(entry.getValue());
                var beans =
                        entry.getValue().context.getBeansOfType(type).entrySet().stream()
                                .filter(bean -> !bean.getKey().startsWith("__lumiraDependency."))
                                .map(Map.Entry::getValue)
                                .toList();
                if (beans.isEmpty() || !isModuleEnabled(entry.getKey())) continue;
                if (TransactionSynchronizationManager.isSynchronizationActive()
                        && TransactionSynchronizationManager.isActualTransactionActive()) {
                    var activeLease = lease;
                    TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                                @Override
                                public void afterCompletion(int status) {
                                    try {
                                        activeLease.close();
                                    } catch (Exception error) {
                                        throw new IllegalStateException(error);
                                    }
                                }
                            });
                    deferred = true;
                }
                try (var scope = invocationScope(entry.getValue())) {
                    for (var bean : beans) out.add(call.apply(bean));
                }
            } catch (ResponseStatusException unavailable) {
                if (!"moduleUnavailable".equals(unavailable.getReason())) throw unavailable;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                if (!deferred && lease != null) {
                    try {
                        lease.close();
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }
            }
        }
        return out;
    }

    public boolean authorize(
            String method, String path, Set<String> authorities, boolean authenticated) {
        if (!isReadyForTraffic()) return false;
        var requestPath = PluginHttpRuleIndex.requestPath(path);
        for (var a : artifacts.values()) {
            var rule = rulesFor(a).first(method, requestPath);
            if (rule != null && isModuleEnabled(a.manifest().id()))
                return ruleAllows(rule, authorities, authenticated);
        }
        return false;
    }

    /** Legacy `authorities` retain any-of semantics; the additive all-of list is explicit. */
    private static boolean ruleAllows(
            PluginManifest.Rule rule, Set<String> authorities, boolean authenticated) {
        return rule.signedCallback()
                || rule.publicAccess()
                || authenticated
                        && (rule.authenticatedOnly() || authorityRuleMatches(rule, authorities));
    }

    static boolean authorityRuleMatches(PluginManifest.Rule rule, Set<String> authorities) {
        if (rule == null || authorities == null) return false;
        boolean legacyAny =
                rule.authorities().isEmpty()
                        || rule.authorities().stream().anyMatch(authorities::contains);
        return legacyAny && authorities.containsAll(rule.allAuthorities());
    }

    static boolean authorityRuleMatches(
            PluginManifest.Rule rule, Set<String> effective, Set<String> global) {
        return scopedRuleMatches(rule, effective, global::contains);
    }

    private static boolean scopedRuleMatches(
            PluginManifest.Rule rule,
            Set<String> effective,
            java.util.function.Predicate<String> global) {
        java.util.function.Predicate<String> allowed =
                permission ->
                        effective.contains(permission)
                                && (!"global"
                                                .equals(
                                                        rule.authorityScopes()
                                                                .getOrDefault(
                                                                        permission,
                                                                        rule.global()
                                                                                ? "global"
                                                                                : "scoped"))
                                        || global.test(permission));
        return rule.allAuthorities().stream().allMatch(allowed)
                && (rule.authorities().isEmpty() || rule.authorities().stream().anyMatch(allowed))
                && (rule.globalAuthorities().isEmpty()
                        || rule.globalAuthorities().stream().anyMatch(global));
    }

    @Override
    public boolean signedCallback(String method, String path) {
        if (!"POST".equals(method) || !isReadyForTraffic()) return false;
        for (var artifact : artifacts.values()) {
            for (var rule : artifact.manifest().apiRules())
                if (rule.signedCallback()
                        && "POST".equals(rule.method())
                        && rule.path().equals(path)
                        && isModuleEnabled(artifact.manifest().id())) return true;
        }
        return false;
    }

    @Override
    public boolean previewReadOnly(
            String method,
            String path,
            Set<String> effectiveAuthorities,
            Set<String> effectiveGlobalAuthorities) {
        if (!"GET".equals(method) || !isReadyForTraffic()) return false;
        var requestPath = PluginHttpRuleIndex.requestPath(path);
        for (var artifact : artifacts.values()) {
            if (rulesFor(artifact)
                    .any(
                            method,
                            requestPath,
                            rule ->
                                    rule.previewReadOnly()
                                            && isModuleEnabled(artifact.manifest().id())
                                            && authorityRuleMatches(
                                                    rule,
                                                    effectiveAuthorities,
                                                    effectiveGlobalAuthorities))) return true;
        }
        return false;
    }

    @Override
    public boolean safeFrontendAsset(String path) {
        if (!isReadyForTraffic()) return false;
        var matcher =
                java.util.regex.Pattern.compile(
                                "^/api/platform/modules/plugins/assets/([a-f0-9]{64})/(plugin\\.js|[A-Za-z0-9_.-]+\\.css)$")
                        .matcher(path == null ? "" : path);
        if (!matcher.matches()) return false;
        String digest = matcher.group(1);
        String file = matcher.group(2);
        return artifacts.values().stream()
                .filter(artifact -> artifact.digest().equals(digest))
                .filter(artifact -> isModuleEnabled(artifact.manifest().id()))
                .anyMatch(artifact -> artifact.manifest().files().containsKey("frontend/" + file));
    }

    public boolean endpointAuthorized(
            String module, String method, String path, Authentication auth) {
        if (!isReadyForTraffic()) return false;
        var a = artifacts.get(module);
        if (a == null) return false;
        var rule = rulesFor(a).first(method, PluginHttpRuleIndex.requestPath(path));
        if (rule != null) {
            // The MVC handler has already established ownership. Another plugin's broader
            // public rule must not authorize this controller, nor trigger a second catalog
            // scan.
            if (!isModuleEnabled(module)
                    || !ruleAllows(
                            rule,
                            auth == null
                                    ? Set.of()
                                    : auth.getAuthorities().stream()
                                            .map(
                                                    org.springframework.security.core
                                                                    .GrantedAuthority
                                                            ::getAuthority)
                                            .collect(java.util.stream.Collectors.toSet()),
                            auth != null
                                    && auth.isAuthenticated()
                                    && !(auth
                                            instanceof
                                            org.springframework.security.authentication
                                                    .AnonymousAuthenticationToken))) return false;
            if (!rule.authorityScopes().isEmpty()) {
                var effective =
                        auth.getAuthorities().stream()
                                .map(
                                        org.springframework.security.core.GrantedAuthority
                                                ::getAuthority)
                                .collect(java.util.stream.Collectors.toSet());
                return scopedRuleMatches(
                        rule,
                        effective,
                        permission -> guard.hasRequestGlobal(auth.getName(), permission));
            }
            if (rule.global() && !rule.publicAccess()) {
                if (!rule.authorities().isEmpty()
                        && rule.authorities().stream()
                                .noneMatch(
                                        permission ->
                                                guard.hasRequestGlobal(auth.getName(), permission)))
                    guard.requireGlobal(auth.getName(), rule.authorities().getFirst());
                for (String permission : rule.allAuthorities())
                    if (!guard.hasRequestGlobal(auth.getName(), permission))
                        guard.requireGlobal(auth.getName(), permission);
            }
            if (!rule.globalAuthorities().isEmpty()
                    && rule.globalAuthorities().stream()
                            .noneMatch(
                                    permission ->
                                            guard.hasRequestGlobal(auth.getName(), permission)))
                guard.requireGlobal(auth.getName(), rule.globalAuthorities().getFirst());
            return true;
        }
        return false;
    }

    public Path asset(String digest, String file) {
        requireRuntimeReady();
        var a =
                artifacts.values().stream()
                        .filter(x -> x.digest().equals(digest))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "pluginNotFound"));
        if (!isModuleEnabled(a.manifest().id())
                || !a.manifest().files().containsKey("frontend/" + file))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "moduleUnavailable");
        Path p = a.directory().resolve("frontend").resolve(file).normalize();
        if (!p.startsWith(a.directory().resolve("frontend")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return p;
    }

    @Override
    public void close() {
        ready = false;
        snapshotCache.clear();
        ModuleStateStore.invalidateScope();
        clusterExecutor.shutdownNow();
        executor.shutdown();
        heartbeatExecutor.shutdownNow();
        try {
            if (!clusterExecutor.awaitTermination(35, TimeUnit.SECONDS)) {
                System.getLogger(getClass().getName())
                        .log(
                                System.Logger.Level.ERROR,
                                "Plugin cluster participant is still running; owned contexts cannot be closed concurrently");
                return;
            }
            if (!executor.awaitTermination(35, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        for (String id : List.copyOf(handles.keySet()))
            try {
                unload(id);
            } catch (Exception e) {
                System.getLogger(getClass().getName())
                        .log(System.Logger.Level.WARNING, "Plugin shutdown failed", e);
            }
        if (nodeRegistered) {
            try {
                jdbc.sql("DELETE FROM platform_plugin_runtime_node WHERE node_id=:id")
                        .param("id", nodeId)
                        .update();
                nodeRegistered = false;
            } catch (RuntimeException failure) {
                System.getLogger(getClass().getName())
                        .log(
                                System.Logger.Level.WARNING,
                                "Plugin runtime node cleanup failed",
                                failure);
            }
        }
    }
}
