package com.lumira.platform.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.modules.ModuleRegistry;
import com.lumira.platform.modules.ModuleStateStore;
import com.lumira.platform.modules.PlatformModule;
import com.lumira.platform.modules.PluginPermissions;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class PluginRuntimeApprovalDatabaseTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private TransactionTemplate tx;
    private String schema;

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_plugin_approval_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute(
                "CREATE TABLE platform_plugin_artifact(module_id text NOT NULL,version text NOT NULL,digest text NOT NULL,PRIMARY KEY(module_id,version,digest));"
                        + "CREATE TABLE platform_plugin_installation(module_id text PRIMARY KEY,digest text NOT NULL,installed boolean NOT NULL,explicit_install boolean NOT NULL,updated_at timestamptz NOT NULL DEFAULT now());"
                        + "CREATE TABLE platform_module_state(id uuid PRIMARY KEY,module_id text UNIQUE NOT NULL,enabled boolean NOT NULL,version bigint NOT NULL,updated_at timestamptz NOT NULL DEFAULT now());"
                        + "CREATE TABLE platform_plugin_graph_approval(id integer PRIMARY KEY CHECK(id=1),graph_revision varchar(64) NOT NULL,approved_at timestamptz NOT NULL DEFAULT now(),approved_by varchar(100) NOT NULL);"
                        + "CREATE TABLE platform_plugin_runtime_node(node_id uuid PRIMARY KEY,started_at timestamptz NOT NULL DEFAULT now(),heartbeat_at timestamptz NOT NULL DEFAULT now(),ready boolean NOT NULL,fixed_graph boolean NOT NULL,graph_revision varchar(64) NOT NULL);"
                        + "CREATE TABLE platform_plugin_operation(id uuid PRIMARY KEY,module_id text NOT NULL,kind varchar(20) NOT NULL,status varchar(20) NOT NULL,step text NOT NULL,error_code text,data_mode varchar(16) NOT NULL,owner_node_id uuid NOT NULL,lease_expires_at timestamptz,updated_at timestamptz NOT NULL DEFAULT now());");
        jdbc.update(
                "INSERT INTO platform_plugin_artifact(module_id,version,digest) VALUES('sample','1.0.0',?)",
                "a".repeat(64));
        jdbc.update(
                "INSERT INTO platform_plugin_installation(module_id,digest,installed,explicit_install) VALUES('sample',?,true,true)",
                "a".repeat(64));
        jdbc.update(
                "INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(?,?,false,1)",
                UUID.randomUUID(),
                "sample");
        jdbc.update(
                "INSERT INTO platform_plugin_graph_approval(id,graph_revision,approved_by) VALUES(1,'initial','fixture')");
    }

    @AfterEach
    void cleanup() {
        PluginPermissions.replaceAll(Map.of());
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void moduleChangeApprovalCommitsWithStateAndFreshRuntimeHealthUsesPersistedRevision(
            @TempDir Path temporaryDirectory) throws Exception {
        Path rootA = Files.createDirectories(temporaryDirectory.resolve("runtime-a"));
        PluginRuntime runtimeA = newRuntime(rootA);
        try {
            tx.executeWithoutResult(
                    status -> {
                        jdbc.update(
                                "UPDATE platform_module_state SET enabled=true,version=version+1 WHERE module_id='sample'");
                        runtimeA.recordChange("sample", true, "fixture-admin");
                    });

            String approved = approval();
            assertThat(approved).isEqualTo(revision(runtimeA));
            assertThat(approved).isNotEqualTo("initial");

            tx.executeWithoutResult(
                    status -> {
                        jdbc.update(
                                "UPDATE platform_module_state SET enabled=false,version=version+1 WHERE module_id='sample'");
                        runtimeA.recordChange("sample", false, "fixture-admin");
                        status.setRollbackOnly();
                    });
            assertThat(approval()).isEqualTo(approved);
            assertThat(revision(runtimeA)).isEqualTo(approved);
        } finally {
            runtimeA.close();
        }

        Path rootB = Files.createDirectories(temporaryDirectory.resolve("runtime-b"));
        PluginRuntime restarted = newRuntime(rootB);
        try {
            String restoredRevision = revision(restarted);
            assertThat(restoredRevision).isEqualTo(approval());
            UUID nodeId = (UUID) field(restarted, "nodeId");
            jdbc.update(
                    "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) VALUES(?,true,false,?)",
                    nodeId,
                    restoredRevision);
            setField(restarted, "ready", true);
            setField(restarted, "heartbeatHealthy", true);
            setField(restarted, "loadedGraphRevision", restoredRevision);

            assertThat(restarted.health().getStatus()).isEqualTo(Status.UP);
        } finally {
            restarted.close();
        }
    }

    @Test
    void unreadyPeerDoesNotTakeDownReadyNodeAndApprovalMismatchStillDoes(
            @TempDir Path temporaryDirectory) throws Exception {
        PluginRuntime runtimeA =
                newRuntime(Files.createDirectories(temporaryDirectory.resolve("a")));
        PluginRuntime runtimeB =
                newRuntime(Files.createDirectories(temporaryDirectory.resolve("b")));
        try {
            String currentRevision = revision(runtimeA);
            jdbc.update(
                    "UPDATE platform_plugin_graph_approval SET graph_revision=? WHERE id=1",
                    currentRevision);
            markRuntimeReady(runtimeA, currentRevision, true);
            markRuntimeReady(runtimeB, currentRevision, false);

            assertThat(runtimeA.health().getStatus()).isEqualTo(Status.UP);
            assertThat(runtimeA.health().getDetails()).containsEntry("unreadyPeers", 1L);
            assertThat(runtimeB.health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(runtimeB.health().getDetails())
                    .containsEntry("reason", "pluginRuntimeNodeNotReady");

            jdbc.update(
                    "UPDATE platform_plugin_graph_approval SET graph_revision='unapproved-revision' WHERE id=1");
            assertThat(runtimeA.health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(runtimeA.health().getDetails())
                    .containsEntry("reason", "pluginGraphApprovalMismatch");
        } finally {
            runtimeA.close();
            runtimeB.close();
        }
    }

    @Test
    void failedDeleteUninstallKeepsApprovedStateReadyAndPreserveUninstallStillWorks(
            @TempDir Path temporaryDirectory) throws Exception {
        PluginRuntime runtime =
                newRuntime(Files.createDirectories(temporaryDirectory.resolve("runtime")));
        try {
            String digest = "a".repeat(64);
            var manifest =
                    new PluginManifest(
                            1,
                            "sample",
                            "Sample",
                            "1.0.0",
                            "1",
                            "com.example.SamplePlugin",
                            "backend/plugin.jar",
                            "frontend/plugin.js",
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            Map.of(),
                            List.of(
                                    new PluginManifest.DataSet(
                                            "plugin_sample_records", "Records", null, null)));
            artifacts(runtime)
                    .put(
                            "sample",
                            new PluginArchive.Artifact(digest, manifest, temporaryDirectory));
            jdbc.execute(
                    "CREATE TABLE plugin_sample_records(id integer PRIMARY KEY, value text NOT NULL);"
                            + "CREATE TABLE plugin_sample_record_references(id integer PRIMARY KEY,record_id integer NOT NULL REFERENCES plugin_sample_records(id));");
            jdbc.update("INSERT INTO plugin_sample_records(id,value) VALUES(1,'retained')");
            jdbc.update("INSERT INTO plugin_sample_record_references(id,record_id) VALUES(1,1)");
            jdbc.update("UPDATE platform_module_state SET enabled=true WHERE module_id='sample'");

            String initialRevision = revision(runtime);
            jdbc.update(
                    "UPDATE platform_plugin_graph_approval SET graph_revision=? WHERE id=1",
                    initialRevision);
            markRuntimeReady(runtime, initialRevision, true);

            var deletePlan = runtime.plan("fixture-admin", "sample", "UNINSTALL", "DELETE");
            var failed =
                    awaitTerminal(
                            runtime,
                            "fixture-admin",
                            runtime.execute(
                                    "fixture-admin",
                                    "sample",
                                    "UNINSTALL",
                                    new PluginRuntime.Execute(
                                            deletePlan.revision(), "DELETE", "sample")));

            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.errorCode()).isEqualTo("pluginDataDeletionFailed");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT installed FROM platform_plugin_installation WHERE module_id='sample'",
                                    Boolean.class))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT enabled FROM platform_module_state WHERE module_id='sample'",
                                    Boolean.class))
                    .isTrue();
            assertThat(approval()).isEqualTo(initialRevision);
            assertThat(revision(runtime)).isEqualTo(initialRevision);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM plugin_sample_records", Long.class))
                    .isEqualTo(1L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM plugin_sample_record_references",
                                    Long.class))
                    .isEqualTo(1L);
            awaitRuntimeReady(runtime);
            assertThat(runtime.isReadyForTraffic()).isTrue();
            assertThat(runtime.health().getStatus()).isEqualTo(Status.UP);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT ready FROM platform_plugin_runtime_node WHERE node_id=?",
                                    Boolean.class,
                                    field(runtime, "nodeId")))
                    .isTrue();

            var preservePlan = runtime.plan("fixture-admin", "sample", "UNINSTALL", "PRESERVE");
            var preserved =
                    awaitTerminal(
                            runtime,
                            "fixture-admin",
                            runtime.execute(
                                    "fixture-admin",
                                    "sample",
                                    "UNINSTALL",
                                    new PluginRuntime.Execute(
                                            preservePlan.revision(), "PRESERVE", null)));
            assertThat(preserved.status()).isEqualTo("SUCCEEDED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT installed FROM platform_plugin_installation WHERE module_id='sample'",
                                    Boolean.class))
                    .isFalse();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM plugin_sample_records", Long.class))
                    .isEqualTo(1L);
            awaitRuntimeReady(runtime);
            assertThat(runtime.isReadyForTraffic()).isTrue();
        } finally {
            runtime.close();
        }
    }

    @Test
    void failedPreserveTeardownAttemptsEveryReleaseAndDoesNotApproveMissingRuntime(
            @TempDir Path temporaryDirectory) throws Exception {
        PluginRuntime runtime = newRuntime(temporaryDirectory);
        var context =
                new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        context.registerBean(
                "disposable",
                org.springframework.beans.factory.DisposableBean.class,
                () -> () -> closed.incrementAndGet());
        context.refresh();
        var resources = new PluginOwnedResources("sample", () -> () -> {});
        resources.own("probe", () -> closed.incrementAndGet());
        var manager = mock(org.pf4j.PluginManager.class);
        org.mockito.Mockito.when(manager.stopPlugin("sample"))
                .thenThrow(new IllegalStateException("stop fixture"));
        setField(runtime, "manager", manager);
        Class<?> handleType = Class.forName(PluginRuntime.class.getName() + "$Handle");
        var constructor = handleType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        @SuppressWarnings("unchecked")
        var handles = (Map<String, Object>) field(runtime, "handles");
        var mapping =
                new org.springframework.web.servlet.mvc.method.annotation
                        .RequestMappingHandlerMapping();
        var route =
                org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths(
                                "/fixture-plugin-route")
                        .build();
        Object handler = new Object();
        var handlerMethod = Object.class.getMethod("toString");
        mapping.registerMapping(route, handler, handlerMethod);
        @SuppressWarnings("unchecked")
        ObjectProvider<
                        org.springframework.web.servlet.mvc.method.annotation
                                .RequestMappingHandlerMapping>
                provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getObject()).thenReturn(mapping);
        setField(runtime, "mappingProvider", provider);
        Class<?> publishedType = Class.forName(PluginRuntime.class.getName() + "$Published");
        var publishedConstructor = publishedType.getDeclaredConstructors()[0];
        publishedConstructor.setAccessible(true);
        Object published = publishedConstructor.newInstance(route, handler, handlerMethod);
        handles.put("sample", constructor.newInstance(context, List.of(published), resources));
        var manifest =
                new PluginManifest(
                        1,
                        "sample",
                        "Sample",
                        "1.0.0",
                        "1",
                        "com.example.SamplePlugin",
                        "backend/plugin.jar",
                        "frontend/plugin.js",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of());
        artifacts(runtime)
                .put(
                        "sample",
                        new PluginArchive.Artifact("a".repeat(64), manifest, temporaryDirectory));
        jdbc.update("UPDATE platform_module_state SET enabled=true WHERE module_id='sample'");
        String before = revision(runtime);
        jdbc.update(
                "UPDATE platform_plugin_graph_approval SET graph_revision=? WHERE id=1", before);
        markRuntimeReady(runtime, before, true);
        try {
            var plan = runtime.plan("fixture-admin", "sample", "UNINSTALL", "PRESERVE");
            var result =
                    awaitTerminal(
                            runtime,
                            "fixture-admin",
                            runtime.execute(
                                    "fixture-admin",
                                    "sample",
                                    "UNINSTALL",
                                    new PluginRuntime.Execute(plan.revision(), "PRESERVE", null)));
            assertThat(result.status()).isEqualTo("FAILED");
            org.mockito.Mockito.verify(manager).unloadPlugin("sample");
            assertThat(mapping.getHandlerMethods()).doesNotContainKey(route);
            assertThat(context.isActive()).isFalse();
            assertThat(closed.get()).isEqualTo(2);
            assertThat(handles).doesNotContainKey("sample");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT installed FROM platform_plugin_installation WHERE module_id='sample'",
                                    Boolean.class))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT enabled FROM platform_module_state WHERE module_id='sample'",
                                    Boolean.class))
                    .isTrue();
            assertThat(approval()).isEqualTo(before);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((boolean) field(runtime, "busy") && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertThat(runtime.isReadyForTraffic()).isFalse();
            assertThat(runtime.health().getStatus()).isEqualTo(Status.DOWN);
        } finally {
            runtime.close();
            context.close();
            resources.close();
        }
    }

    @SuppressWarnings("unchecked")
    private PluginRuntime newRuntime(Path root) {
        return new PluginRuntime(
                JdbcClient.create(dataSource),
                new JsonMapper(),
                new ModuleRegistry(
                        List.of(
                                new PlatformModule(
                                        "identity", "Identity", "1.0", List.of(), true))),
                mock(ModuleStateStore.class),
                mock(AdministrationPort.class),
                mock(AuditPort.class),
                mock(ConfigurableApplicationContext.class),
                mock(ObjectProvider.class),
                new DataSourceTransactionManager(dataSource),
                root.toString(),
                dataSource,
                false,
                root.resolve("approved-graph.json").toString());
    }

    private String approval() {
        return jdbc.queryForObject(
                "SELECT graph_revision FROM platform_plugin_graph_approval WHERE id=1",
                String.class);
    }

    private void markRuntimeReady(PluginRuntime runtime, String graphRevision, boolean nodeReady)
            throws Exception {
        UUID nodeId = (UUID) field(runtime, "nodeId");
        jdbc.update(
                "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) VALUES(?,?,false,?)",
                nodeId,
                nodeReady,
                graphRevision);
        setField(runtime, "ready", true);
        setField(runtime, "heartbeatHealthy", true);
        setField(runtime, "loadedGraphRevision", graphRevision);
        setField(runtime, "nodeRegistered", true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, PluginArchive.Artifact> artifacts(PluginRuntime runtime)
            throws Exception {
        return (Map<String, PluginArchive.Artifact>) field(runtime, "artifacts");
    }

    private PluginRuntime.Operation awaitTerminal(
            PluginRuntime runtime, String actor, PluginRuntime.Operation operation)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(operation.status())
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
            operation = runtime.operation(actor, operation.id());
        }
        return operation;
    }

    private void awaitRuntimeReady(PluginRuntime runtime) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean ready = false;
        while (!ready && System.nanoTime() < deadline) {
            ready =
                    runtime.isReadyForTraffic()
                            && runtime.health().getStatus().equals(Status.UP)
                            && Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT ready FROM platform_plugin_runtime_node WHERE node_id=?",
                                            Boolean.class,
                                            field(runtime, "nodeId")));
            if (!ready) Thread.sleep(20);
        }
        assertThat(ready).isTrue();
    }

    private static String revision(PluginRuntime runtime) throws Exception {
        var method = PluginRuntime.class.getDeclaredMethod("revision");
        method.setAccessible(true);
        return (String) method.invoke(runtime);
    }

    private static Object field(PluginRuntime runtime, String name) throws Exception {
        Field field = PluginRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(runtime);
    }

    private static void setField(PluginRuntime runtime, String name, Object value)
            throws Exception {
        Field field = PluginRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(runtime, value);
    }
}
