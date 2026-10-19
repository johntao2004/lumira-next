/**
 * 编排可信插件的恢复、装配、健康检查和安装卸载，以批准图、节点租约及调用排空协调运行状态，恢复失败时阻止业务流量。 Orchestrates trusted-plugin restoration,
 * assembly, health checks and installation or removal using approved graphs, node leases and call
 * draining, blocking business traffic when recovery fails.
 */
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
import org.springframework.http.server.PathContainer;
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
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

/**
 * 单实例编排器；每个已发布扩展都有归属明确、可排空在途调用的上下文。 Single-instance orchestrator. Every published extension has an
 * owned, drainable context.
 */
@Service
public class PluginRuntime
        implements AutoCloseable,
                PluginAuthorizationPort,
                PluginExtensionsPort,
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

    private record RuntimeGraphSnapshot(String revision, String approvedRevision) {}

    private static final class Handle {
        final AnnotationConfigApplicationContext context;
        final List<Published> mappings;
        final PluginOwnedResources resources;
        boolean accepting = true;
        int calls;

        Handle(
                AnnotationConfigApplicationContext context,
                List<Published> mappings,
                PluginOwnedResources resources) {
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
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final ConfigurableApplicationContext host;
    private final ObjectProvider<RequestMappingHandlerMapping> mappingProvider;
    private final TransactionTemplate tx;
    private final Path root;
    private final DataSource dataSource;
    private final Path approvedGraphPath;
    private final boolean fixedGraph;
    private final UUID nodeId = UUID.randomUUID();
    private final PluginManager manager;
    private final Map<String, PluginArchive.Artifact> artifacts = new ConcurrentHashMap<>();
    private final Map<String, Handle> handles = new ConcurrentHashMap<>();
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().name("lumira-plugin-lifecycle").factory());
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("lumira-plugin-heartbeat").factory());
    private final Object lock = new Object();
    private volatile boolean busy;
    private volatile boolean ready;
    private volatile boolean heartbeatHealthy;
    private volatile boolean nodeRegistered;
    private volatile String loadedGraphRevision = "";

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
            DataSource dataSource,
            @Value("${lumira.plugins.fixed-graph:false}") boolean fixedGraph,
            @Value("${lumira.plugins.approved-graph:plugins/approved-graph.json}")
                    String approvedGraph) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.registry = registry;
        this.states = states;
        this.guard = guard;
        this.audit = audit;
        this.host = host;
        this.mappingProvider = mappingProvider;
        tx = new TransactionTemplate(transactions);
        root = Path.of(path).toAbsolutePath().normalize();
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
                                                // 宿主契约优先；插件也可以在自身 api 包中定义响应 DTO。
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
        if ("INSTALL".equals(kind))
            com.lumira.platform.common.PlatformInstallAuthority.require(guard, actor);
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
                withClusterLifecycleLock(
                        () -> {
                            restoreUnderClusterLock();
                            return null;
                        });
            } catch (Exception | Error failure) {
                ready = false;
                updateNodeState(false, "");
                throw failure;
            }
        }
    }

    private void restoreUnderClusterLock() throws Exception {
        com.lumira.platform.common.NavigationMenuGrantAligner.alignHost(jdbc);
        Files.createDirectories(root.resolve("artifacts"));
        Files.createDirectories(root.resolve("loaded"));
        recoverExpiredOperations();
        boolean bootstrap =
                jdbc.sql("SELECT count(*) FROM platform_plugin_bootstrap")
                                .query(Integer.class)
                                .single()
                        == 0;
        Map<String, ApprovedPlugin> approved =
                fixedGraph ? readApprovedPackages(bootstrap) : Map.of();
        if (fixedGraph) reconcileApprovedInstallations(approved, bootstrap);
        for (var row :
                jdbc.sql(
                                "SELECT digest,manifest::text FROM platform_plugin_artifact ORDER BY created_at")
                        .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2)))
                        .list()) {
            if (fixedGraph
                    && approved.values().stream().noneMatch(p -> p.digest().equals(row.getKey())))
                continue;
            var manifest = mapper.readValue(row.getValue(), PluginManifest.class);
            var artifact =
                    new PluginArchive.Artifact(
                            row.getKey(),
                            manifest,
                            root.resolve("artifacts").resolve(row.getKey()));
            PluginArchive.verifyStored(artifact);
            artifacts.put(manifest.id(), artifact);
        }
        if (bootstrap && !fixedGraph) {
            for (String id : List.of("feedback", "localization", "sensitive-words")) {
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
        // 运行版本由安装摘要确定，不由目录导入时间确定。
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
                artifacts.put(
                        manifest.id(),
                        new PluginArchive.Artifact(
                                installation.digest(),
                                manifest,
                                root.resolve("artifacts").resolve(installation.digest())));
            }
        registerCatalog();
        if (bootstrap && !fixedGraph) {
            tx.executeWithoutResult(
                    status -> {
                        for (var a : artifacts.values()) {
                            jdbc.sql(
                                            "INSERT INTO platform_plugin_installation(module_id,digest,installed,explicit_install) VALUES(:id,:digest,true,true) ON CONFLICT(module_id) DO NOTHING")
                                    .param("id", a.manifest().id())
                                    .param("digest", a.digest())
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
        String currentRevision = revision();
        requireApprovedGraph(currentRevision);
        loadedGraphRevision = currentRevision;
        ready = true;
        updateNodeState(true, loadedGraphRevision);
    }

    private void registerNode() {
        jdbc.sql(
                        "INSERT INTO platform_plugin_runtime_node(node_id,started_at,heartbeat_at,ready,fixed_graph,graph_revision) VALUES(:id,now(),now(),false,:fixed,'')")
                .param("id", nodeId)
                .param("fixed", fixedGraph)
                .update();
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
        try {
            recoverExpiredOperations();
            String currentRevision = revision();
            boolean graphReady = ready && !busy && loadedGraphRevision.equals(currentRevision);
            int updated =
                    jdbc.sql(
                                    "UPDATE platform_plugin_runtime_node SET heartbeat_at=now(),ready=:ready,graph_revision=:revision WHERE node_id=:id")
                            .param("ready", graphReady)
                            .param("revision", loadedGraphRevision)
                            .param("id", nodeId)
                            .update();
            if (updated != 1) throw new IllegalStateException("pluginRuntimeNodeUnavailable");
            jdbc.sql(
                            "UPDATE platform_plugin_operation SET lease_expires_at=now()+interval '45 seconds',updated_at=now() WHERE owner_node_id=:id AND status IN ('QUEUED','RUNNING')")
                    .param("id", nodeId)
                    .update();
            jdbc.sql(
                            "DELETE FROM platform_plugin_runtime_node WHERE node_id<>:id AND heartbeat_at<now()-interval '5 minutes'")
                    .param("id", nodeId)
                    .update();
            heartbeatHealthy = true;
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
        jdbc.sql(
                        "UPDATE platform_plugin_runtime_node SET heartbeat_at=now(),ready=:ready,graph_revision=:revision WHERE node_id=:id")
                .param("ready", nodeReady)
                .param("revision", graphRevision)
                .param("id", nodeId)
                .update();
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

    private void reconcileApprovedInstallations(
            Map<String, ApprovedPlugin> approved, boolean bootstrap) {
        if (!bootstrap && !approvedDatabaseMatches(approved)) {
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
        tx.executeWithoutResult(
                status -> {
                    Set<String> included = new HashSet<>();
                    for (ApprovedPlugin plugin : approved.values()) {
                        included.add(plugin.id());
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
                        Installation current = installationMap().get(plugin.id());
                        boolean installed =
                                bootstrap
                                        ? isBootstrapInstalled(plugin.id())
                                        : current != null && current.installed();
                        boolean explicit =
                                bootstrap
                                        ? isBootstrapInstalled(plugin.id())
                                        : current != null && current.explicitInstall();
                        jdbc.sql(
                                        "INSERT INTO platform_plugin_installation(module_id,digest,installed,explicit_install) VALUES(:id,:digest,:installed,:explicit) ON CONFLICT(module_id) DO UPDATE SET digest=EXCLUDED.digest,installed=EXCLUDED.installed,explicit_install=EXCLUDED.explicit_install,updated_at=now()")
                                .param("id", plugin.id())
                                .param("digest", plugin.digest())
                                .param("installed", installed)
                                .param("explicit", explicit)
                                .update();
                        Boolean previousEnabled =
                                jdbc.sql(
                                                "SELECT enabled FROM platform_module_state WHERE module_id=:id")
                                        .param("id", plugin.id())
                                        .query(Boolean.class)
                                        .optional()
                                        .orElse(false);
                        boolean enabled =
                                bootstrap
                                        ? isBootstrapInstalled(plugin.id())
                                        : installed && previousEnabled;
                        jdbc.sql(
                                        "INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(:uuid,:id,:enabled,1) ON CONFLICT(module_id) DO UPDATE SET enabled=EXCLUDED.enabled,version=platform_module_state.version+1,updated_at=now() WHERE platform_module_state.enabled IS DISTINCT FROM EXCLUDED.enabled")
                                .param("uuid", UUID.randomUUID())
                                .param("id", plugin.id())
                                .param("enabled", enabled)
                                .update();
                    }
                    if (!included.isEmpty()) {
                        jdbc.sql(
                                        "UPDATE platform_plugin_installation SET installed=false,explicit_install=false,updated_at=now() WHERE module_id NOT IN (:ids)")
                                .param("ids", included)
                                .update();
                    }
                    if (bootstrap) {
                        jdbc.sql(
                                        "INSERT INTO platform_plugin_bootstrap(id) VALUES(1) ON CONFLICT DO NOTHING")
                                .update();
                    }
                });
    }

    private static boolean isBootstrapInstalled(String id) {
        return Set.of("feedback", "localization", "sensitive-words").contains(id);
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
        for (Installation installation : installationMap().values()) {
            ApprovedPlugin packageEntry = approved.get(installation.moduleId());
            if (installation.installed() && packageEntry == null) return false;
            if (packageEntry != null && !packageEntry.digest().equals(installation.digest()))
                return false;
        }
        return true;
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
                                        "UPDATE platform_plugin_graph_approval SET graph_revision=:revision,approved_at=now(),approved_by='approved-image' WHERE id=1")
                                .param("revision", graphRevision)
                                .update();
                    } else if (!approved.equals(graphRevision)) {
                        throw new IllegalStateException("pluginGraphApprovalMismatch");
                    }
                });
    }

    private void approveGraphChange(String actor) {
        String currentRevision = revision();
        jdbc.sql(
                        "UPDATE platform_plugin_graph_approval SET graph_revision=:revision,approved_at=now(),approved_by=:actor WHERE id=1")
                .param("revision", currentRevision)
                .param("actor", actor)
                .update();
        loadedGraphRevision = currentRevision;
        updateNodeState(!busy, currentRevision);
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
        return ready && !busy && heartbeatHealthy && loadedGraphRevision.equals(revision());
    }

    private boolean localRuntimeReady() {
        return ready && !busy && heartbeatHealthy;
    }

    private boolean localRuntimeReady(RuntimeGraphSnapshot snapshot) {
        return localRuntimeReady()
                && snapshot.revision().equals(loadedGraphRevision)
                && snapshot.revision().equals(snapshot.approvedRevision());
    }

    private void requireLifecycleChangeAllowed() {
        if (fixedGraph) throw conflict("pluginFixedGraph");
        if (activeNodeCount() > 1) throw conflict("pluginClusterLifecycleUnsupported");
        if (!graphMatchesLoadedSnapshot()) throw conflict("pluginGraphChanged");
    }

    private void requireOperationTopologyAllowed() {
        if (fixedGraph) throw conflict("pluginFixedGraph");
        if (activeNodeCount() > 1) throw conflict("pluginClusterLifecycleUnsupported");
        if (!ready || !heartbeatHealthy || !loadedGraphRevision.equals(revision()))
            throw conflict("pluginGraphChanged");
    }

    private String lifecycleBlockReason() {
        if (fixedGraph) return "pluginFixedGraph";
        if (activeNodeCount() > 1) return "pluginClusterLifecycleUnsupported";
        if (busy) return "pluginBusy";
        return graphMatchesLoadedSnapshot() ? null : "pluginGraphChanged";
    }

    @Override
    public void requireChangeAllowed(String moduleId, boolean enabled) {
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
     * 仅在事务提交后发布本地状态；节点记录稍后由心跳执行器写入。
     *
     * <p>Only publishes local state after commit; the heartbeat executor writes the node row later.
     */
    private void publishCommittedGraph(String committedRevision) {
        if (committedRevision == null) {
            ready = false;
            return;
        }
        loadedGraphRevision = committedRevision;
        ready = heartbeatHealthy && !busy;
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

    public boolean isReadyForTraffic() {
        if (!localRuntimeReady()) return false;
        try {
            return localRuntimeReady(graphSnapshot());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private void requireRuntimeReady() {
        if (!isReadyForTraffic())
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "pluginRuntimeNotReady");
    }

    private void registerCatalog() {
        for (String id : orderAll()) {
            var m = artifacts.get(id).manifest();
            registry.registerPlugin(
                    new PlatformModule(
                            m.id(),
                            m.name(),
                            m.version(),
                            m.dependencies().stream().map(PluginManifest.Dependency::id).toList()));
        }

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
        var graph = graph();
        var ids = new LinkedHashSet<String>();
        for (String id : artifacts.keySet().stream().sorted().toList())
            ids.addAll(graph.install(id));
        return ids.stream().filter(artifacts::containsKey).toList();
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
                .orElse(true);
    }

    private String revision() {
        return graphSnapshot().revision();
    }

    /**
     * 在同一 PostgreSQL 语句快照中读取全部修订输入及其批准记录。 Reads all revision inputs and its approval in one
     * PostgreSQL statement snapshot.
     */
    private RuntimeGraphSnapshot graphSnapshot() {
        return jdbc.sql(
                        """
                            WITH artifact_rows AS (
                                SELECT COALESCE(
                                    jsonb_agg(jsonb_build_array(module_id, version, digest)
                                        ORDER BY module_id, version, digest),
                                    '[]'::jsonb)::text AS value
                                FROM platform_plugin_artifact
                            ), installation_rows AS (
                                SELECT COALESCE(
                                    jsonb_agg(jsonb_build_array(module_id, digest, installed, explicit_install)
                                        ORDER BY module_id),
                                    '[]'::jsonb)::text AS value
                                FROM platform_plugin_installation
                            ), module_rows AS (
                                SELECT COALESCE(
                                    jsonb_agg(jsonb_build_array(module_id, enabled, version)
                                        ORDER BY module_id),
                                    '[]'::jsonb)::text AS value
                                FROM platform_module_state
                            )
                            SELECT artifact_rows.value AS artifacts,
                                installation_rows.value AS installations,
                                module_rows.value AS module_states,
                                COALESCE((SELECT graph_revision
                                    FROM platform_plugin_graph_approval WHERE id = 1), '')
                                    AS approved_revision
                            FROM artifact_rows CROSS JOIN installation_rows CROSS JOIN module_rows
                            """)
                .query(
                        (rs, row) -> {
                            String revision =
                                    PluginArchive.digest(
                                            mapper.writeValueAsBytes(
                                                    new RuntimeRevisionSnapshot(
                                                            rs.getString("artifacts"),
                                                            rs.getString("installations"),
                                                            rs.getString("module_states"))));
                            return new RuntimeGraphSnapshot(
                                    revision, rs.getString("approved_revision"));
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
        return artifacts.values().stream()
                .sorted(Comparator.comparing(a -> a.manifest().id()))
                .map(
                        a -> {
                            var i = installationMap().get(a.manifest().id());
                            boolean in = i != null && i.installed();
                            return new View(
                                    a.manifest().id(),
                                    a.manifest().name(),
                                    a.manifest().version(),
                                    a.digest(),
                                    in,
                                    in && states.isEnabled(a.manifest().id()),
                                    i != null && i.explicitInstall(),
                                    !in
                                            ? "UNINSTALLED"
                                            : handles.containsKey(a.manifest().id())
                                                    ? "RUNNING"
                                                    : storedEnabled(a.manifest().id())
                                                            ? "FAILED"
                                                            : "STOPPED",
                                    a.manifest(),
                                    lifecycleBlockReason == null,
                                    lifecycleBlockReason);
                        })
                .toList();
    }

    public List<View> frontend() {
        requireRuntimeReady();
        return views().stream().filter(View::enabled).toList();
    }

    public List<View> importPackage(String actor, byte[] bytes) throws Exception {
        guard.requireGlobal(actor, PlatformPageId.MODULES.accessPermission());
        guard.requireGlobal(actor, PlatformOperation.MODULE_INSTALL.permission());
        com.lumira.platform.common.PlatformInstallAuthority.require(guard, actor);
        requireLifecycleChangeAllowed();
        return withClusterLifecycleLock(
                () -> {
                    synchronized (lock) {
                        requireLifecycleChangeAllowed();
                        var before = new HashMap<>(artifacts);
                        try {
                            tx.executeWithoutResult(
                                    status -> {
                                        try {
                                            importArchive(bytes, 0);
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
                            approveGraphChange(actor);
                        } catch (Exception e) {
                            artifacts.clear();
                            artifacts.putAll(before);
                            throw e;
                        }
                        return views();
                    }
                });
    }

    private void importArchive(byte[] bytes, int depth) throws Exception {
        if (depth > 16) throw conflict("pluginDependencyCycle");
        var a = PluginArchive.unpack(bytes, root.resolve("artifacts"), mapper);
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
        String lifecycleBlock = lifecycleBlockReason();
        if (lifecycleBlock != null) blockers.add(lifecycleBlock);
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
                remove.addAll(graph().uninstall(id));
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
                        (revision() + id + kind + dataMode + mapper.writeValueAsString(data))
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Plan(
                id, kind, revision, install, reuse, enable, remove, blockers, dataMode, supported,
                data);
    }

    public Operation execute(String actor, String id, String kind, Execute input) {
        requireLifecyclePermission(actor, kind);
        requireRuntimeReady();
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
            updateNodeState(false, loadedGraphRevision);
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

    private void step(UUID id, String status, String step, String error) {
        int updated =
                jdbc.sql(
                                "UPDATE platform_plugin_operation SET status=:status,step=:step,error_code=:error,lease_expires_at=CASE WHEN :status IN ('SUCCEEDED','FAILED','CANCELLED') THEN NULL ELSE now()+interval '45 seconds' END,updated_at=now() WHERE id=:id AND owner_node_id=:owner AND status IN ('QUEUED','RUNNING') AND lease_expires_at>now()")
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
        var uninstallCommitted = new AtomicReference<>(false);
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
                            if (plan.kind().equals("INSTALL")) {
                                for (String id : plan.install()) {
                                    step(op, "RUNNING", "install " + id, null);
                                    load(id, msg -> step(op, "RUNNING", msg, null));
                                    added.add(id);
                                    persistInstalled(id, true, id.equals(plan.moduleId()));
                                    setFlag(id, true);
                                }
                                for (String id : plan.enable()) {
                                    step(op, "RUNNING", "enable " + id, null);
                                    load(id);
                                    setFlag(id, true);
                                    enabled.add(id);
                                }
                                if (installed(plan.moduleId()))
                                    persistInstalled(plan.moduleId(), true, true);
                                String completedRevision =
                                        completeLifecycleOperation(actor, op, "plugin.installed");
                                loadedGraphRevision = completedRevision;
                                ready = true;
                                updateNodeState(true, completedRevision);
                            } else {
                                // 移除任何提供方前，先排空所有受影响句柄的在途调用。
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
                                    } finally {
                                        if (wasLoaded && !handles.containsKey(id)) removed.add(id);
                                    }
                                }
                                if (deleteUninstall)
                                    step(op, "RUNNING", "delete data " + plan.moduleId(), null);
                                String completedRevision = completeUninstall(actor, op, plan);
                                uninstallCommitted.set(true);
                                loadedGraphRevision = completedRevision;
                                ready = true;
                                updateNodeState(true, completedRevision);
                            }
                        }
                        return null;
                    });
        } catch (Throwable error) {
            boolean restoreRemovedResources =
                    plan.kind().equals("UNINSTALL") && !uninstallCommitted.get();
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
            busy = false;
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
                                persistInstalled(id, false, false);
                                setFlag(id, false);
                            } catch (Exception ignored) {
                            }
                        }
                        for (String id : enabled) {
                            try {
                                unload(id);
                                setFlag(id, false);
                            } catch (Exception ignored) {
                            }
                        }
                        if (restoreRemovedResources) {
                            // 失败事务释放生命周期锁后，其他节点可能已修改模块图；仅当旧图仍是此运行时唯一批准图时才重新加载。
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
                        // 排空失败时，恢复所有仍已发布句柄的调用准入。
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

    private String completeLifecycleOperation(String actor, UUID op, String action) {
        tx.executeWithoutResult(status -> audit.append(actor, action, op));
        registerCatalog();
        String completedRevision = revision();
        tx.executeWithoutResult(
                status -> {
                    jdbc.sql(
                                    "UPDATE platform_plugin_graph_approval SET graph_revision=:revision,approved_at=now(),approved_by=:actor WHERE id=1")
                            .param("revision", completedRevision)
                            .param("actor", actor)
                            .update();
                    step(op, "SUCCEEDED", "complete", null);
                });
        return completedRevision;
    }

    private String completeUninstall(String actor, UUID op, Plan plan) {
        return tx.execute(
                status -> {
                    for (String id : plan.remove()) {
                        persistInstalled(id, false, false);
                        setFlag(id, false);
                    }
                    if (plan.dataMode().equals("DELETE")) {
                        PluginDataRemoval.delete(
                                jdbc, artifacts.get(plan.moduleId()).manifest().dataSets());
                        audit.append(actor, "plugin.dataDeleted", op);
                    }
                    audit.append(actor, "plugin.uninstalled", op);
                    registerCatalog();
                    String completedRevision = revision();
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
                            if (busy) throw conflict("pluginBusy");
                            if (!installed(id)) throw conflict("pluginNotInstalled");
                            if (states.states().stream()
                                            .filter(s -> s.moduleId().equals(id))
                                            .findFirst()
                                            .orElseThrow()
                                            .version()
                                    != version) throw conflict("staleRecord");
                            busy = true;
                            updateNodeState(false, loadedGraphRevision);
                            try {
                                ModuleStateStore.State changed;
                                if (enabled) {
                                    boolean alreadyLoaded = handles.containsKey(id);
                                    loadUnchecked(id);
                                    try {
                                        changed =
                                                states.change(
                                                        id, true, version, actor, changeReason);
                                    } catch (Exception e) {
                                        if (!alreadyLoaded)
                                            try {
                                                unload(id);
                                            } catch (Exception ignored) {
                                            }
                                        throw e;
                                    }
                                } else {
                                    drain(id);
                                    try {
                                        changed =
                                                states.change(
                                                        id, false, version, actor, changeReason);
                                        unload(id);
                                    } catch (Exception e) {
                                        var current =
                                                states.states().stream()
                                                        .filter(s -> s.moduleId().equals(id))
                                                        .findFirst()
                                                        .orElse(null);
                                        if (current != null && !current.enabled())
                                            try {
                                                states.change(
                                                        id,
                                                        true,
                                                        current.version(),
                                                        actor,
                                                        "Restore after failed plugin shutdown");
                                                load(id);
                                            } catch (Exception ignored) {
                                            }
                                        throw conflict("pluginDrainTimeout");
                                    }
                                }
                                approveGraphChange(actor);
                                return changed;
                            } finally {
                                busy = false;
                                String currentRevision = revision();
                                String approvedRevision =
                                        jdbc.sql(
                                                        "SELECT graph_revision FROM platform_plugin_graph_approval WHERE id=1")
                                                .query(String.class)
                                                .optional()
                                                .orElse("");
                                ready =
                                        currentRevision.equals(loadedGraphRevision)
                                                && currentRevision.equals(approvedRevision)
                                                && heartbeatHealthy;
                                updateNodeState(ready, loadedGraphRevision);
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
        var a = artifacts.get(id);
        Path jar = root.resolve("loaded").resolve(id + ".jar");
        Files.copy(
                a.directory().resolve(a.manifest().backend()),
                jar,
                StandardCopyOption.REPLACE_EXISTING);
        var mappings = new ArrayList<Published>();
        var published = new ArrayList<Published>();
        var context = new AnnotationConfigApplicationContext();
        var resources = new PluginOwnedResources(id, () -> acquire(id));
        try {
            manager.loadPlugin(jar);
            var wrapper = manager.getPlugin(id);
            if (!(wrapper.getPlugin() instanceof PluginEntry entry))
                throw new IllegalArgumentException("pluginEntryIncompatible");
            context.setParent(host);
            context.setClassLoader(wrapper.getPluginClassLoader());
            context.registerBean(com.lumira.plugin.sdk.PluginResources.class, () -> resources);
            progress.accept("create container " + id);
            context.register(entry.configuration());
            context.refresh();
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
            // 只执行前向迁移，每个插件使用独立的迁移历史表。
            // Apply only forward migrations, with a distinct table per plugin.
            progress.accept("migrate " + id);
            Path migrations = a.directory().resolve("migrations");
            if (Files.isDirectory(migrations)) {
                var migrationConnection =
                        host.getBean(com.lumira.platform.common.DatabaseMigrationConnection.class);
                try {
                    PluginPermissionMigrationRunner.migrate(
                            migrationConnection.source(), id, migrations);
                } finally {
                    migrationConnection.protectHistory(
                            "plugin_" + id.replace('-', '_') + "_schema_history");
                }
            }
            progress.accept("health check " + id);
            entry.checkHealth();
            manager.startPlugin(id);
            progress.accept("publish " + id);
            for (var p : mappings) {
                mapping.registerMapping(p.mapping(), p.bean(), p.method());
                published.add(p);
            }
            handles.put(id, new Handle(context, List.copyOf(mappings), resources));
            registry.live(id, true);
            registerNavigation(a.manifest());
            resources.start();
        } catch (Throwable e) {
            registry.live(id, false);
            handles.remove(id);
            try {
                release(id, published, resources, context);
            } catch (Throwable cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
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

    private boolean covered(PluginManifest m, String method, String path) {
        return m.apiRules().stream()
                .anyMatch(r -> r.method().equals(method) && matches(r.path(), path));
    }

    private static boolean matches(String pattern, String path) {
        try {
            return PathPatternParser.defaultInstance
                    .parse(pattern)
                    .matches(PathContainer.parsePath(path));
        } catch (Exception e) {
            return false;
        }
    }

    private void validateNavigation(PluginManifest m) {
        for (var r : m.routes()) {
            var owner =
                    jdbc.sql(
                                    "SELECT COALESCE(module_id,'') FROM platform_navigation_menu WHERE section_key=:id AND NOT deleted")
                            .param("id", r.id())
                            .query(String.class)
                            .optional();
            if (owner.isPresent() && !m.id().equals(owner.get()))
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
                if (!m.id().equals(existing.get())) throw conflict("pluginRouteConflict");
                com.lumira.platform.common.NavigationMenuGrantAligner.alignBuiltIn(
                        jdbc,
                        new com.lumira.platform.common.NavigationMenuGrantAligner.Desired(
                                r.id(), r.permission(), r.global()),
                        m.id());
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
        release(id, h.mappings, h.resources, h.context);
    }

    /**
     * 每项资源释放都必须尝试；回调失败不能让路由或类加载器继续存活。
     *
     * <p>Every release is attempted; a failed callback must not leave routes or a classloader live.
     */
    private void release(
            String id,
            List<Published> mappings,
            PluginOwnedResources resources,
            AnnotationConfigApplicationContext context)
            throws Exception {
        var actions = new ArrayList<AutoCloseable>();
        for (var published : mappings)
            actions.add(() -> mappingProvider.getObject().unregisterMapping(published.mapping()));
        actions.add(resources);
        actions.add(context::close);
        actions.add(() -> manager.stopPlugin(id));
        actions.add(() -> manager.unloadPlugin(id));
        PluginCleanup.closeAll(actions);
    }

    public AutoCloseable acquire(String id) {
        var h = handles.get(id);
        if (h == null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "moduleUnavailable");
        synchronized (h) {
            if (!h.accepting)
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "moduleUnavailable");
            h.calls++;
        }
        return () -> {
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
            if (!states.isEnabled(entry.getKey())) continue;
            try (var lease = acquire(entry.getKey())) {
                for (var bean : entry.getValue().context.getBeansOfType(type).values())
                    out.add(call.apply(bean));
            } catch (ResponseStatusException unavailable) {
                if (!"moduleUnavailable".equals(unavailable.getReason())) throw unavailable;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    public boolean authorize(
            String method, String path, Set<String> authorities, boolean authenticated) {
        if (!isReadyForTraffic()) return false;
        for (var a : artifacts.values()) {
            if (!states.isEnabled(a.manifest().id())) continue;
            for (var rule : a.manifest().apiRules())
                if (rule.method().equals(method) && matches(rule.path(), path))
                    return rule.signedCallback()
                            || rule.publicAccess()
                            || authenticated
                                    && (rule.authenticatedOnly()
                                            || authorityRuleMatches(rule, authorities));
        }
        return false;
    }

    /**
     * 旧 authorities 保留满足任一项的语义；新增的全部满足列表显式声明。 Legacy `authorities` retain any-of semantics; the
     * additive all-of list is explicit.
     */
    static boolean authorityRuleMatches(PluginManifest.Rule rule, Set<String> authorities) {
        if (rule == null || authorities == null) return false;
        boolean legacyAny =
                rule.authorities().isEmpty()
                        || rule.authorities().stream().anyMatch(authorities::contains);
        return legacyAny && authorities.containsAll(rule.allAuthorities());
    }

    @Override
    public boolean signedCallback(String method, String path) {
        if (!"POST".equals(method) || !isReadyForTraffic()) return false;
        for (var artifact : artifacts.values()) {
            if (!states.isEnabled(artifact.manifest().id())) continue;
            for (var rule : artifact.manifest().apiRules())
                if (rule.signedCallback()
                        && "POST".equals(rule.method())
                        && rule.path().equals(path)) return true;
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
        for (var artifact : artifacts.values()) {
            if (!states.isEnabled(artifact.manifest().id())) continue;
            for (var rule : artifact.manifest().apiRules()) {
                if (!rule.previewReadOnly()
                        || !rule.method().equals(method)
                        || !matches(rule.path(), path)) continue;
                Set<String> available =
                        rule.global() ? effectiveGlobalAuthorities : effectiveAuthorities;
                if (authorityRuleMatches(rule, available)
                        && (rule.globalAuthorities().isEmpty()
                                || rule.globalAuthorities().stream()
                                        .anyMatch(effectiveGlobalAuthorities::contains)))
                    return true;
            }
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
                .filter(artifact -> states.isEnabled(artifact.manifest().id()))
                .anyMatch(artifact -> artifact.manifest().files().containsKey("frontend/" + file));
    }

    public boolean endpointAuthorized(
            String module, String method, String path, Authentication auth) {
        if (!isReadyForTraffic()) return false;
        var a = artifacts.get(module);
        if (a == null) return false;
        for (var rule : a.manifest().apiRules())
            if (rule.method().equals(method) && matches(rule.path(), path)) {
                if (!authorize(
                        method,
                        path,
                        auth == null
                                ? Set.of()
                                : auth.getAuthorities().stream()
                                        .map(
                                                org.springframework.security.core.GrantedAuthority
                                                        ::getAuthority)
                                        .collect(java.util.stream.Collectors.toSet()),
                        auth != null
                                && auth.isAuthenticated()
                                && !(auth
                                        instanceof
                                        org.springframework.security.authentication
                                                .AnonymousAuthenticationToken))) return false;
                if (rule.global() && !rule.publicAccess()) {
                    if (!rule.authorities().isEmpty()
                            && rule.authorities().stream()
                                    .noneMatch(
                                            permission ->
                                                    guard.hasGlobal(auth.getName(), permission)))
                        guard.requireGlobal(auth.getName(), rule.authorities().getFirst());
                    for (String permission : rule.allAuthorities())
                        if (!guard.hasGlobal(auth.getName(), permission))
                            guard.requireGlobal(auth.getName(), permission);
                }
                if (!rule.globalAuthorities().isEmpty()
                        && rule.globalAuthorities().stream()
                                .noneMatch(
                                        permission -> guard.hasGlobal(auth.getName(), permission)))
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
        if (!states.isEnabled(a.manifest().id())
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
        executor.shutdown();
        heartbeatExecutor.shutdownNow();
        try {
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
