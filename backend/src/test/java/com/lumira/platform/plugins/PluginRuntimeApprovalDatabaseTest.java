package com.lumira.platform.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.lumira.platform.modules.ModuleRegistry;
import com.lumira.platform.modules.PlatformModule;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
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
                        + "CREATE TABLE platform_plugin_installation(module_id text PRIMARY KEY,digest text NOT NULL,installed boolean NOT NULL,explicit_install boolean NOT NULL);"
                        + "CREATE TABLE platform_module_state(id uuid PRIMARY KEY,module_id text UNIQUE NOT NULL,enabled boolean NOT NULL,version bigint NOT NULL);"
                        + "CREATE TABLE platform_plugin_graph_approval(id integer PRIMARY KEY CHECK(id=1),graph_revision varchar(64) NOT NULL,approved_at timestamptz NOT NULL DEFAULT now(),approved_by varchar(100) NOT NULL);"
                        + "CREATE TABLE platform_plugin_runtime_node(node_id uuid PRIMARY KEY,started_at timestamptz NOT NULL DEFAULT now(),heartbeat_at timestamptz NOT NULL DEFAULT now(),ready boolean NOT NULL,fixed_graph boolean NOT NULL,graph_revision varchar(64) NOT NULL);");
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

    @SuppressWarnings("unchecked")
    private PluginRuntime newRuntime(Path root) {
        return new PluginRuntime(
                JdbcClient.create(dataSource),
                new JsonMapper(),
                new ModuleRegistry(
                        List.of(
                                new PlatformModule(
                                        "identity", "Identity", "1.0", List.of(), true))),
                null,
                null,
                null,
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
