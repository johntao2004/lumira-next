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
    private java.util.function.Function<String, List<String>> businessReferences = id -> List.of();

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
        for (String table :
                List.of(
                        "platform_plugin_artifact",
                        "platform_plugin_installation",
                        "platform_module_state",
                        "platform_plugin_graph_approval",
                        "platform_plugin_runtime_node",
                        "platform_plugin_operation")) {
            jdbc.execute(com.lumira.platform.TestSchema.table(table));
        }
        jdbc.update(
                "INSERT INTO platform_plugin_artifact(module_id,version,digest,manifest) VALUES('sample','1.0.0',?,CAST(? AS jsonb))",
                "a".repeat(64),
                new JsonMapper()
                        .writeValueAsString(
                                bootstrapFixtureArtifact(
                                                "sample", "a".repeat(64), List.of(), Path.of("."))
                                        .manifest()));
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
    void projectedGraphMatchesActualInstallAndRemovalWithoutLeakingRolledBackMetadata(
            @TempDir Path directory) throws Exception {
        var runtime = newRuntime(directory);
        try {
            jdbc.update(
                    "INSERT INTO platform_plugin_artifact(module_id,version,digest,manifest) VALUES('fresh','1.0.0',?,CAST('{}' AS jsonb))",
                    "b".repeat(64));
            String original = revision(runtime);
            String projected =
                    PluginGraphProjection.project(
                            JdbcClient.create(dataSource),
                            new JsonMapper(),
                            List.of(
                                    new PluginRuntime.Installation(
                                            "sample", "a".repeat(64), true, false),
                                    new PluginRuntime.Installation(
                                            "fresh", "b".repeat(64), true, true)),
                            Map.of("sample", true, "fresh", true));
            tx.executeWithoutResult(
                    status -> {
                        jdbc.update(
                                "UPDATE platform_plugin_installation SET explicit_install=false WHERE module_id='sample'");
                        jdbc.update(
                                "INSERT INTO platform_plugin_installation(module_id,digest,installed,explicit_install) VALUES('fresh',?,true,true)",
                                "b".repeat(64));
                        jdbc.update(
                                "UPDATE platform_module_state SET enabled=true,version=version+1 WHERE module_id='sample'");
                        jdbc.update(
                                "INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(?,'fresh',true,1)",
                                UUID.randomUUID());
                        try {
                            assertThat(revision(runtime)).isEqualTo(projected);
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                        status.setRollbackOnly();
                    });
            assertThat(revision(runtime)).isEqualTo(original);
            String removal =
                    PluginGraphProjection.project(
                            JdbcClient.create(dataSource),
                            new JsonMapper(),
                            List.of(
                                    new PluginRuntime.Installation(
                                            "sample", "a".repeat(64), false, false)),
                            Map.of("sample", false));
            tx.executeWithoutResult(
                    status -> {
                        jdbc.update(
                                "UPDATE platform_plugin_installation SET installed=false,explicit_install=false WHERE module_id='sample'");
                        jdbc.update(
                                "UPDATE platform_module_state SET enabled=false,version=version+1 WHERE module_id='sample'");
                        try {
                            assertThat(revision(runtime)).isEqualTo(removal);
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                        status.setRollbackOnly();
                    });
            assertThat(revision(runtime)).isEqualTo(original);
        } finally {
            runtime.close();
        }
    }

    @Test
    void unfinishedClusterDecisionBlocksLegacyLifecycleIncludingInfrastructureRemoval(
            @TempDir Path directory) throws Exception {
        var runtime = newRuntime(directory);
        try {
            jdbc.execute("CREATE TABLE plugin_cluster_runtime_operation(phase text NOT NULL)");
            jdbc.update("INSERT INTO plugin_cluster_runtime_operation VALUES('COMMITTED')");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> runtime.requireChangeAllowed("cluster-runtime", false))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("pluginClusterOperationActive");
            var validate = PluginRuntime.class.getDeclaredMethod("requireOperationTopologyAllowed");
            validate.setAccessible(true);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> validate.invoke(runtime))
                    .hasRootCauseMessage("409 CONFLICT \"pluginClusterOperationActive\"");
            jdbc.update("UPDATE plugin_cluster_runtime_operation SET phase='ABORTED'");
            assertThat(
                            ((ClusterOperationJournal) field(runtime, "clusterJournal"))
                                    .blocksGraphChange())
                    .isFalse();
        } finally {
            runtime.close();
        }
    }

    @Test
    void fixedReleaseRequiresManualDependencyRemovalEvenWhenBothModulesAreOmitted(
            @TempDir Path directory) throws Exception {
        var runtime = newRuntime(directory);
        try {
            var approved = prepareFixedRemovalFixture(runtime, directory, true);
            var before =
                    jdbc.queryForList(
                            "SELECT * FROM platform_plugin_installation ORDER BY module_id");
            var states =
                    jdbc.queryForList("SELECT * FROM platform_module_state ORDER BY module_id");
            var failure =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            org.springframework.web.server.ResponseStatusException.class,
                            () ->
                                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                            runtime,
                                            "reconcileApprovedInstallations",
                                            approved,
                                            false));
            assertThat(failure.getReason()).isEqualTo("pluginRequiredBy:sample");
            assertThat(
                            jdbc.queryForList(
                                    "SELECT * FROM platform_plugin_installation ORDER BY module_id"))
                    .isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT * FROM platform_module_state ORDER BY module_id"))
                    .isEqualTo(states);
            jdbc.execute(
                    "CREATE TABLE retained_release_records(id int PRIMARY KEY); INSERT INTO retained_release_records VALUES(1)");
            var firstStep = new java.util.HashMap<>(approved);
            firstStep.put(
                    "provider",
                    new PluginRuntime.ApprovedPlugin("provider", "1.0.0", "b".repeat(64)));
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    runtime, "reconcileApprovedInstallations", firstStep, false);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT installed FROM platform_plugin_installation WHERE module_id='provider'",
                                    Boolean.class))
                    .isTrue();
            jdbc.update(
                    "UPDATE platform_plugin_graph_approval SET graph_revision=? WHERE id=1",
                    revision(runtime));
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    runtime, "reconcileApprovedInstallations", approved, false);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM platform_plugin_installation WHERE installed",
                                    Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM retained_release_records", Integer.class))
                    .isEqualTo(1);
        } finally {
            runtime.close();
        }
    }

    @Test
    void fixedReleaseChecksPersistedBusinessReferencesBeforeRemovingAProvider(
            @TempDir Path directory) throws Exception {
        var runtime = newRuntime(directory);
        try {
            var approved = prepareFixedRemovalFixture(runtime, directory, false);
            businessReferences =
                    id ->
                            id.equals("sample")
                                    ? List.of("dictionaryReferenced:settings")
                                    : List.of();
            var before =
                    jdbc.queryForList(
                            "SELECT * FROM platform_plugin_installation ORDER BY module_id");
            var failure =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            org.springframework.web.server.ResponseStatusException.class,
                            () ->
                                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                            runtime,
                                            "reconcileApprovedInstallations",
                                            approved,
                                            false));
            assertThat(failure.getReason()).isEqualTo("dictionaryReferenced:settings");
            assertThat(
                            jdbc.queryForList(
                                    "SELECT * FROM platform_plugin_installation ORDER BY module_id"))
                    .isEqualTo(before);
        } finally {
            runtime.close();
        }
    }

    private Map<String, PluginRuntime.ApprovedPlugin> prepareFixedRemovalFixture(
            PluginRuntime runtime, Path directory, boolean dependent) throws Exception {
        prepareConfiguredDependencyFixture(runtime, directory);
        var optional = bootstrapFixtureArtifact("optional", "c".repeat(64), List.of(), directory);
        artifacts(runtime).put("optional", optional);
        jdbc.update(
                "INSERT INTO platform_plugin_artifact(module_id,version,digest,manifest) VALUES('optional','1.0.0',?,CAST(? AS jsonb))",
                optional.digest(),
                new JsonMapper().writeValueAsString(optional.manifest()));
        jdbc.update(
                "INSERT INTO platform_plugin_installation(module_id,digest,installed,explicit_install) VALUES('optional',?,false,false)",
                optional.digest());
        jdbc.update(
                "INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(?,'optional',false,1)",
                UUID.randomUUID());
        if (dependent) {
            var provider =
                    bootstrapFixtureArtifact("provider", "b".repeat(64), List.of(), directory);
            artifacts(runtime).put("provider", provider);
            jdbc.update(
                    "INSERT INTO platform_plugin_artifact(module_id,version,digest,manifest) VALUES('provider','1.0.0',?,CAST(? AS jsonb))",
                    provider.digest(),
                    new JsonMapper().writeValueAsString(provider.manifest()));
            jdbc.update(
                    "INSERT INTO platform_plugin_installation(module_id,digest,installed,explicit_install) VALUES('provider',?,true,true)",
                    provider.digest());
            jdbc.update(
                    "INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(?,'provider',true,1)",
                    UUID.randomUUID());
            var consumer =
                    bootstrapFixtureArtifact(
                            "sample",
                            "a".repeat(64),
                            List.of(new PluginManifest.Dependency("provider", "1.0.0")),
                            directory);
            artifacts(runtime).put("sample", consumer);
            jdbc.update(
                    "UPDATE platform_plugin_artifact SET manifest=CAST(? AS jsonb) WHERE module_id='sample'",
                    new JsonMapper().writeValueAsString(consumer.manifest()));
        }
        jdbc.update(
                "UPDATE platform_plugin_graph_approval SET graph_revision=? WHERE id=1",
                revision(runtime));
        return Map.of(
                "optional",
                new PluginRuntime.ApprovedPlugin("optional", "1.0.0", optional.digest()));
    }

    @Test
    void fixedReleaseWaitsForLegacyWritersThenReadsTheirCommittedReferences(@TempDir Path directory)
            throws Exception {
        var runtime = newRuntime(directory);
        try {
            prepareConfiguredDependencyFixture(runtime, directory);
            jdbc.execute("CREATE TABLE configured_release_refs(provider text PRIMARY KEY)");
            businessReferences =
                    id ->
                            Boolean.TRUE.equals(
                                            jdbc.queryForObject(
                                                    "SELECT EXISTS(SELECT 1 FROM configured_release_refs WHERE provider=?)",
                                                    Boolean.class,
                                                    id))
                                    ? List.of("dictionaryReferenced:settings")
                                    : List.of();
            var properties = new java.util.Properties();
            properties.setProperty("options", "-c default_transaction_isolation=serializable");
            dataSource.setConnectionProperties(properties);
            try (var writer = dataSource.getConnection();
                    var workers = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                writer.setAutoCommit(false);
                try (var statement = writer.createStatement()) {
                    statement.execute("SELECT pg_advisory_xact_lock_shared(1000481027)");
                    statement.execute("INSERT INTO configured_release_refs VALUES('sample')");
                }
                var publication = (TransactionTemplate) field(runtime, "tx");
                var result =
                        workers.submit(
                                () ->
                                        publication.executeWithoutResult(
                                                status -> {
                                                    assertThat(
                                                                    jdbc.queryForObject(
                                                                            "SHOW transaction_isolation",
                                                                            String.class))
                                                            .isEqualTo("read committed");
                                                    assertThat(
                                                                    jdbc.queryForObject(
                                                                            "SELECT count(*) FROM configured_release_refs",
                                                                            Integer.class))
                                                            .isZero();
                                                    org.springframework.test.util
                                                            .ReflectionTestUtils.invokeMethod(
                                                            runtime,
                                                            "verifyFixedReleaseCanPublish",
                                                            java.util.Set.of("sample"));
                                                }));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (jdbc.queryForObject(
                                "SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid WHERE a.datname=current_database() AND l.locktype='advisory' AND l.classid=0 AND l.objid=1000481027 AND l.objsubid=1 AND NOT l.granted",
                                Integer.class)
                        == 0) {
                    assertThat(result.isDone())
                            .as("Publication should wait on the legacy recovery barrier")
                            .isFalse();
                    assertThat(System.nanoTime()).isLessThan(deadline);
                    Thread.sleep(10);
                }
                writer.commit();
                var failure =
                        org.junit.jupiter.api.Assertions.assertThrows(
                                java.util.concurrent.ExecutionException.class,
                                () -> result.get(3, TimeUnit.SECONDS));
                assertThat(failure.getCause())
                        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
                assertThat(
                                ((org.springframework.web.server.ResponseStatusException)
                                                failure.getCause())
                                        .getReason())
                        .isEqualTo("dictionaryReferenced:settings");
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT installed FROM platform_plugin_installation WHERE module_id='sample'",
                                        Boolean.class))
                        .isTrue();
            }
        } finally {
            runtime.close();
        }
    }

    @Test
    void unchangedApprovedStartupDoesNotRewriteMetadataOrWaitForBusinessTransactions(
            @TempDir Path directory) throws Exception {
        var runtime = newRuntime(directory);
        try (var business = new PluginFencedDataSource(dataSource).getConnection()) {
            prepareConfiguredDependencyFixture(runtime, directory);
            jdbc.update(
                    "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) VALUES(?,true,true,?)",
                    UUID.randomUUID(),
                    revision(runtime));
            business.setAutoCommit(false);
            var owner =
                    new PluginInvocationContext.Owner(
                            "sample", "a".repeat(64), 1, (UUID) field(runtime, "nodeId"));
            try (var scope = PluginInvocationContext.open(owner);
                    var statement = business.createStatement()) {
                statement.execute("SELECT 1");
            }
            jdbc.execute(
                    """
                    CREATE FUNCTION reject_startup_metadata_write() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'unchanged startup must not write metadata'; END $$;
                    CREATE TRIGGER reject_startup_write BEFORE INSERT OR UPDATE OR DELETE ON platform_plugin_installation
                    FOR EACH ROW EXECUTE FUNCTION reject_startup_metadata_write();
                    CREATE TRIGGER reject_startup_state_write BEFORE INSERT OR UPDATE OR DELETE ON platform_module_state
                    FOR EACH ROW EXECUTE FUNCTION reject_startup_metadata_write();
                    """);
            var before = jdbc.queryForList("SELECT * FROM platform_plugin_installation");
            var properties = new java.util.Properties();
            properties.setProperty("options", "-c lock_timeout=500ms");
            dataSource.setConnectionProperties(properties);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    runtime,
                    "reconcileApprovedInstallations",
                    Map.of(
                            "sample",
                            new PluginRuntime.ApprovedPlugin("sample", "1.0.0", "a".repeat(64))),
                    false);
            assertThat(jdbc.queryForList("SELECT * FROM platform_plugin_installation"))
                    .isEqualTo(before);
            jdbc.update(
                    "UPDATE platform_plugin_graph_approval SET graph_revision='unapproved' WHERE id=1");
            var mismatch =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            org.springframework.web.server.ResponseStatusException.class,
                            () ->
                                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                            runtime,
                                            "reconcileApprovedInstallations",
                                            Map.of(
                                                    "sample",
                                                    new PluginRuntime.ApprovedPlugin(
                                                            "sample", "1.0.0", "a".repeat(64))),
                                            false));
            assertThat(mismatch.getReason()).isEqualTo("pluginGraphApprovalMismatch");
            business.rollback();
        } finally {
            runtime.close();
        }
    }

    @Test
    void approvedReleaseCannotCrossAnExpiredNodesUnfinishedTransaction(@TempDir Path directory)
            throws Exception {
        var runtime = newRuntime(directory);
        try (var business = new PluginFencedDataSource(dataSource).getConnection()) {
            prepareConfiguredDependencyFixture(runtime, directory);
            UUID oldNode = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) VALUES(?,true,true,?)",
                    oldNode,
                    revision(runtime));
            business.setAutoCommit(false);
            try (var scope =
                            PluginInvocationContext.open(
                                    new PluginInvocationContext.Owner(
                                            "sample", "a".repeat(64), 1, oldNode));
                    var statement = business.createStatement()) {
                statement.execute("SELECT 1");
            }
            jdbc.update(
                    "UPDATE platform_plugin_runtime_node SET heartbeat_at=clock_timestamp()-interval '31 seconds' WHERE node_id=?",
                    oldNode);
            var oldArtifact = artifacts(runtime).get("sample");
            artifacts(runtime)
                    .put(
                            "sample",
                            new PluginArchive.Artifact(
                                    "b".repeat(64), oldArtifact.manifest(), directory));
            jdbc.update(
                    "INSERT INTO platform_plugin_artifact(module_id,version,digest,manifest) VALUES('sample','1.1.0',?,'{}'::jsonb)",
                    "b".repeat(64));
            jdbc.update(
                    "UPDATE platform_plugin_graph_approval SET graph_revision=? WHERE id=1",
                    revision(runtime));
            var properties = new java.util.Properties();
            properties.setProperty("options", "-c lock_timeout=500ms");
            dataSource.setConnectionProperties(properties);
            var approved =
                    Map.of(
                            "sample",
                            new PluginRuntime.ApprovedPlugin("sample", "1.1.0", "b".repeat(64)));
            var failure =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            org.springframework.web.server.ResponseStatusException.class,
                            () ->
                                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                            runtime,
                                            "reconcileApprovedInstallations",
                                            approved,
                                            false));
            assertThat(failure.getReason()).isEqualTo("pluginDrainTimeout");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT digest FROM platform_plugin_installation WHERE module_id='sample'",
                                    String.class))
                    .isEqualTo("a".repeat(64));
            business.rollback();
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    runtime, "reconcileApprovedInstallations", approved, false);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT digest FROM platform_plugin_installation WHERE module_id='sample'",
                                    String.class))
                    .isEqualTo("b".repeat(64));
        } finally {
            runtime.close();
        }
    }

    @Test
    void approvedReleaseDisablesOnlyRemovedPluginsAndPreservesTheirData(@TempDir Path directory)
            throws Exception {
        var runtime = newRuntime(directory);
        try {
            prepareConfiguredDependencyFixture(runtime, directory);
            jdbc.update(
                    "INSERT INTO platform_plugin_artifact(module_id,version,digest,manifest) VALUES('orphan','1.0.0',?,CAST(? AS jsonb))",
                    "b".repeat(64),
                    new JsonMapper()
                            .writeValueAsString(
                                    bootstrapFixtureArtifact(
                                                    "orphan", "b".repeat(64), List.of(), directory)
                                            .manifest()));
            jdbc.update(
                    "INSERT INTO platform_plugin_installation(module_id,digest,installed,explicit_install) VALUES('orphan',?,true,true)",
                    "b".repeat(64));
            jdbc.update(
                    "INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(?,'orphan',true,7)",
                    UUID.randomUUID());
            jdbc.execute(
                    "CREATE TABLE orphan_business_records(id int PRIMARY KEY); INSERT INTO orphan_business_records VALUES(1)");
            jdbc.update(
                    "UPDATE platform_plugin_graph_approval SET graph_revision=? WHERE id=1",
                    revision(runtime));
            var sample =
                    jdbc.queryForList(
                            "SELECT * FROM platform_plugin_installation WHERE module_id='sample'");
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    runtime,
                    "reconcileApprovedInstallations",
                    Map.of(
                            "sample",
                            new PluginRuntime.ApprovedPlugin("sample", "1.0.0", "a".repeat(64))),
                    false);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT installed OR explicit_install FROM platform_plugin_installation WHERE module_id='orphan'",
                                    Boolean.class))
                    .isFalse();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT enabled FROM platform_module_state WHERE module_id='orphan'",
                                    Boolean.class))
                    .isFalse();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT version FROM platform_module_state WHERE module_id='orphan'",
                                    Long.class))
                    .isEqualTo(8L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM orphan_business_records", Integer.class))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT * FROM platform_plugin_installation WHERE module_id='sample'"))
                    .isEqualTo(sample);
        } finally {
            runtime.close();
        }
    }

    @Test
    void fixedGraphBootstrapUsesItsManifestAndRespectsAnExistingUninstall(@TempDir Path directory)
            throws Exception {
        var runtime = newRuntime(directory);
        try {
            prepareConfiguredDependencyFixture(runtime, directory);
            jdbc.execute(com.lumira.platform.TestSchema.table("platform_plugin_bootstrap"));
            Files.writeString(directory.resolve("bootstrap.json"), "[\"sample\"]");
            jdbc.update(
                    "UPDATE platform_plugin_installation SET installed=false,explicit_install=false");
            jdbc.update("UPDATE platform_module_state SET enabled=false");
            jdbc.update(
                    "UPDATE platform_plugin_graph_approval SET graph_revision=? WHERE id=1",
                    revision(runtime));
            var approved =
                    Map.of(
                            "sample",
                            new PluginRuntime.ApprovedPlugin("sample", "1.0.0", "a".repeat(64)));
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    runtime, "reconcileApprovedInstallations", approved, true);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT installed AND explicit_install FROM platform_plugin_installation WHERE module_id='sample'",
                                    Boolean.class))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT enabled FROM platform_module_state WHERE module_id='sample'",
                                    Boolean.class))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM platform_plugin_bootstrap",
                                    Integer.class))
                    .isEqualTo(1);
            jdbc.update(
                    "UPDATE platform_plugin_installation SET installed=false,explicit_install=false");
            jdbc.update("UPDATE platform_module_state SET enabled=false,version=version+1");
            jdbc.update(
                    "UPDATE platform_plugin_graph_approval SET graph_revision=? WHERE id=1",
                    revision(runtime));
            var before = jdbc.queryForList("SELECT * FROM platform_plugin_installation");
            Files.delete(directory.resolve("bootstrap.json"));
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    runtime, "reconcileApprovedInstallations", approved, false);
            assertThat(jdbc.queryForList("SELECT * FROM platform_plugin_installation"))
                    .isEqualTo(before);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT enabled FROM platform_module_state WHERE module_id='sample'",
                                    Boolean.class))
                    .isFalse();
        } finally {
            runtime.close();
        }
    }

    @Test
    void fixedGraphBootstrapRejectsAnUnapprovedSelectionBeforeChangingInstallations(
            @TempDir Path directory) throws Exception {
        var runtime = newRuntime(directory);
        try {
            prepareConfiguredDependencyFixture(runtime, directory);
            var before = jdbc.queryForList("SELECT * FROM platform_plugin_installation");
            jdbc.execute(com.lumira.platform.TestSchema.table("platform_plugin_bootstrap"));
            Files.writeString(directory.resolve("bootstrap.json"), "[\"missing-business\"]");
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                    runtime,
                                    "reconcileApprovedInstallations",
                                    Map.of(
                                            "sample",
                                            new PluginRuntime.ApprovedPlugin(
                                                    "sample", "1.0.0", "a".repeat(64))),
                                    true));
            assertThat(jdbc.queryForList("SELECT * FROM platform_plugin_installation"))
                    .isEqualTo(before);
        } finally {
            runtime.close();
        }
    }

    @Test
    void fixedBootstrapInstallsTheDependencyClosureAndKeepsUnselectedCatalogInactive(
            @TempDir Path directory) throws Exception {
        var runtime = newRuntime(directory);
        try {
            prepareConfiguredDependencyFixture(runtime, directory);
            var consumer =
                    bootstrapFixtureArtifact(
                            "sample",
                            "a".repeat(64),
                            List.of(new PluginManifest.Dependency("provider", "1.0.0")),
                            directory);
            artifacts(runtime).put("sample", consumer);
            for (var entry :
                    Map.of("provider", "b".repeat(64), "unused", "c".repeat(64)).entrySet()) {
                artifacts(runtime)
                        .put(
                                entry.getKey(),
                                bootstrapFixtureArtifact(
                                        entry.getKey(), entry.getValue(), List.of(), directory));
                jdbc.update(
                        "INSERT INTO platform_plugin_artifact(module_id,version,digest,manifest) VALUES(?,'1.0.0',?,'{}'::jsonb)",
                        entry.getKey(),
                        entry.getValue());
                jdbc.update(
                        "INSERT INTO platform_plugin_installation(module_id,digest,installed,explicit_install) VALUES(?,?,false,false)",
                        entry.getKey(),
                        entry.getValue());
                jdbc.update(
                        "INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(?,?,false,1)",
                        UUID.randomUUID(),
                        entry.getKey());
            }
            jdbc.execute(com.lumira.platform.TestSchema.table("platform_plugin_bootstrap"));
            Files.writeString(directory.resolve("bootstrap.json"), "[\"sample\"]");
            jdbc.update(
                    "UPDATE platform_plugin_graph_approval SET graph_revision=? WHERE id=1",
                    revision(runtime));
            var approved =
                    Map.of(
                            "sample",
                                    new PluginRuntime.ApprovedPlugin(
                                            "sample", "1.0.0", "a".repeat(64)),
                            "provider",
                                    new PluginRuntime.ApprovedPlugin(
                                            "provider", "1.0.0", "b".repeat(64)),
                            "unused",
                                    new PluginRuntime.ApprovedPlugin(
                                            "unused", "1.0.0", "c".repeat(64)));
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    runtime, "reconcileApprovedInstallations", approved, true);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT module_id FROM platform_plugin_installation WHERE installed ORDER BY module_id",
                                    String.class))
                    .containsExactly("provider", "sample");
            assertThat(
                            jdbc.queryForList(
                                    "SELECT module_id FROM platform_plugin_installation WHERE explicit_install ORDER BY module_id",
                                    String.class))
                    .containsExactly("sample");
            assertThat(
                            jdbc.queryForList(
                                    "SELECT module_id FROM platform_module_state WHERE enabled ORDER BY module_id",
                                    String.class))
                    .containsExactly("provider", "sample");
            assertThat(
                            org.springframework.test.util.ReflectionTestUtils
                                    .<java.util.Set<String>>invokeMethod(
                                            runtime, "bootstrapClosure", List.of()))
                    .isEmpty();
        } finally {
            runtime.close();
        }
    }

    private static PluginArchive.Artifact bootstrapFixtureArtifact(
            String id,
            String digest,
            List<PluginManifest.Dependency> dependencies,
            Path directory) {
        var manifest =
                new PluginManifest(
                        1,
                        id,
                        id,
                        "1.0.0",
                        "1",
                        "com.example.SamplePlugin",
                        "backend/plugin.jar",
                        "frontend/plugin.js",
                        dependencies,
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of());
        return new PluginArchive.Artifact(digest, manifest, directory);
    }

    @Test
    void expiredLocalNodeCannotAdmitAnotherRequestEvenWhenTheGraphIsUnchanged(
            @TempDir Path temporaryDirectory) throws Exception {
        PluginRuntime runtime = newRuntime(temporaryDirectory);
        try {
            String current = revision(runtime);
            jdbc.update(
                    "UPDATE platform_plugin_graph_approval SET graph_revision=? WHERE id=1",
                    current);
            markRuntimeReady(runtime, current, true);
            try (var request = runtime.openTrafficScope()) {
                assertThat(runtime.isReadyForTraffic()).isTrue();
            }
            jdbc.update(
                    "INSERT INTO platform_plugin_runtime_node(node_id,ready,fixed_graph,graph_revision) VALUES(?,true,false,?)",
                    UUID.randomUUID(),
                    current);
            jdbc.update(
                    "UPDATE platform_plugin_runtime_node SET heartbeat_at=clock_timestamp()-interval '31 seconds' WHERE node_id=?",
                    field(runtime, "nodeId"));
            assertThat(runtime.health().getStatus()).isEqualTo(Status.DOWN);
            try (var nextRequest = runtime.openTrafficScope()) {
                assertThat(runtime.isReadyForTraffic()).isFalse();
            }
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> runtime.execute("fixture-admin", "sample", "INSTALL", null))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("503 SERVICE_UNAVAILABLE")
                    .hasMessageContaining("pluginRuntimeNotReady");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM platform_plugin_operation", Long.class))
                    .isZero();
        } finally {
            runtime.close();
        }
    }

    @Test
    void requestReadinessCacheExpiresWithNodeLeaseAndRecoversAfterHeartbeat(
            @TempDir Path temporaryDirectory) throws Exception {
        PluginRuntime runtime = newRuntime(temporaryDirectory);
        try {
            String current = revision(runtime);
            jdbc.update(
                    "UPDATE platform_plugin_graph_approval SET graph_revision=? WHERE id=1",
                    current);
            markRuntimeReady(runtime, current, true);
            jdbc.update(
                    "UPDATE platform_plugin_runtime_node SET heartbeat_at=clock_timestamp()-interval '29 seconds' WHERE node_id=?",
                    field(runtime, "nodeId"));
            try (var request = runtime.openTrafficScope()) {
                assertThat(runtime.isReadyForTraffic()).isTrue();
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                        java.time.Duration.ofSeconds(4),
                        () -> {
                            while (Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT heartbeat_at>clock_timestamp()-interval '30 seconds' FROM platform_plugin_runtime_node WHERE node_id=?",
                                            Boolean.class,
                                            field(runtime, "nodeId")))) Thread.sleep(10);
                        });
                assertThat(runtime.isReadyForTraffic()).isFalse();
            }
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    runtime, "heartbeatTick");
            try (var nextRequest = runtime.openTrafficScope()) {
                assertThat(runtime.isReadyForTraffic()).isTrue();
            }
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    runtime, "heartbeatTick");
            assertThat(runtime.health().getStatus()).isEqualTo(Status.UP);
        } finally {
            runtime.close();
        }
    }

    @Test
    void dispatchReadsGraphOnceAndRefreshesAfterCloseOrLocalTransition(
            @TempDir Path temporaryDirectory) throws Exception {
        PluginRuntime runtime = newRuntime(temporaryDirectory);
        try {
            String initial = revision(runtime);
            jdbc.update(
                    "UPDATE platform_plugin_graph_approval SET graph_revision=? WHERE id=1",
                    initial);
            markRuntimeReady(runtime, initial, true);
            var tracking = org.mockito.Mockito.spy(JdbcClient.create(dataSource));
            setField(runtime, "jdbc", tracking);
            try (var scope = runtime.openTrafficScope()) {
                for (int i = 0; i < 100; i++) assertThat(runtime.isReadyForTraffic()).isTrue();
                org.mockito.Mockito.verify(tracking, org.mockito.Mockito.times(1))
                        .sql(org.mockito.ArgumentMatchers.contains("WITH artifact_rows"));
                // Local publication invalidates even within a dispatch; shutdown always wins.
                setField(runtime, "loadedGraphRevision", "different");
                assertThat(runtime.isReadyForTraffic()).isFalse();
                setField(runtime, "loadedGraphRevision", initial);
                assertThat(runtime.isReadyForTraffic()).isTrue();
                setField(runtime, "heartbeatHealthy", false);
                assertThat(runtime.isReadyForTraffic()).isFalse();
                setField(runtime, "heartbeatHealthy", true);
            }
            // No stale result survives into another request or a background call on this thread.
            jdbc.update(
                    "UPDATE platform_module_state SET version=version+1 WHERE module_id='sample'");
            try (var scope = runtime.openTrafficScope()) {
                assertThat(runtime.isReadyForTraffic()).isFalse();
            }
            assertThat(runtime.isReadyForTraffic()).isFalse();
        } finally {
            runtime.close();
        }
    }

    @Test
    void pluginViewsReadInstallationsAndFlagsOnceForTheWholeCatalog(
            @TempDir Path temporaryDirectory) throws Exception {
        PluginRuntime runtime = newRuntime(temporaryDirectory);
        try {
            var registry = (ModuleRegistry) field(runtime, "registry");
            for (int i = 0; i < 100; i++) {
                String id = "view" + i;
                var manifest = mock(PluginManifest.class);
                org.mockito.Mockito.when(manifest.id()).thenReturn(id);
                org.mockito.Mockito.when(manifest.name()).thenReturn(id);
                org.mockito.Mockito.when(manifest.version()).thenReturn("1.0.0");
                artifacts(runtime)
                        .put(
                                id,
                                new PluginArchive.Artifact(
                                        "b".repeat(64), manifest, temporaryDirectory));
                registry.registerPlugin(new PlatformModule(id, id, "1.0.0", List.of("identity")));
                registry.live(id, true);
                jdbc.update(
                        "INSERT INTO platform_plugin_installation(module_id,digest,installed,explicit_install) VALUES(?,?,true,true)",
                        id,
                        "b".repeat(64));
                jdbc.update(
                        "INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(?,?,true,1)",
                        UUID.randomUUID(),
                        id);
            }
            var tracking = org.mockito.Mockito.spy(JdbcClient.create(dataSource));
            setField(runtime, "jdbc", tracking);
            assertThat(runtime.views()).hasSize(100).allMatch(PluginRuntime.View::enabled);
            org.mockito.Mockito.verify(tracking)
                    .sql(
                            "SELECT module_id,digest,installed,explicit_install FROM platform_plugin_installation");
            org.mockito.Mockito.verify(tracking)
                    .sql("SELECT module_id,enabled FROM platform_module_state");
            org.mockito.Mockito.verify(tracking, org.mockito.Mockito.never())
                    .sql("SELECT enabled FROM platform_module_state WHERE module_id=:id");
            jdbc.update("UPDATE platform_module_state SET enabled=false WHERE module_id='view99'");
            assertThat(
                            runtime.views().stream()
                                    .filter(view -> view.id().equals("view99"))
                                    .findFirst()
                                    .orElseThrow()
                                    .enabled())
                    .isFalse();
        } finally {
            runtime.close();
        }
    }

    @Test
    void scopedLifecycleKeepsUnchangedTrafficButStillRejectsUnrelatedGraphDrift(
            @TempDir Path temporaryDirectory) throws Exception {
        PluginRuntime runtime = newRuntime(temporaryDirectory);
        try {
            String initial = revision(runtime);
            jdbc.update(
                    "UPDATE platform_plugin_graph_approval SET graph_revision=? WHERE id=1",
                    initial);
            markRuntimeReady(runtime, initial, true);
            org.springframework.test.util.ReflectionTestUtils.setField(runtime, "busy", true);
            assertThat(runtime.isReadyForTraffic()).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> runtime.execute("fixture-admin", "sample", "INSTALL", null))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("pluginBusy");
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    runtime, "beginTrafficTransition", java.util.Set.of("sample"));
            jdbc.update(
                    "UPDATE platform_module_state SET enabled=true,version=version+1 WHERE module_id='sample'");
            assertThat(runtime.isReadyForTraffic()).isTrue();
            jdbc.update(
                    "INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(?,'unrelated',true,1)",
                    UUID.randomUUID());
            assertThat(runtime.isReadyForTraffic()).isFalse();
            jdbc.update("DELETE FROM platform_module_state WHERE module_id='unrelated'");
            assertThat(runtime.isReadyForTraffic()).isTrue();
            jdbc.update(
                    "UPDATE platform_plugin_graph_approval SET graph_revision='unexpected' WHERE id=1");
            assertThat(runtime.isReadyForTraffic()).isFalse();
            jdbc.update(
                    "UPDATE platform_plugin_graph_approval SET graph_revision=? WHERE id=1",
                    initial);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    runtime, "trafficTransition", null);
            assertThat(runtime.isReadyForTraffic()).isFalse();
        } finally {
            runtime.close();
        }
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
    void failedImportDoesNotPublishAnUnapprovedArtifactOrGhostModule(@TempDir Path directory)
            throws Exception {
        var runtime = newRuntime(directory);
        try {
            prepareConfiguredDependencyFixture(runtime, directory);
            var before = jdbc.queryForList("SELECT * FROM platform_plugin_artifact");
            String beforeRevision = approval();
            rejectApproval();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    runtime.importPackage(
                                            "fixture-admin",
                                            new PluginArchivePublicationTest().archive()))
                    .isInstanceOf(Exception.class);
            assertThat(jdbc.queryForList("SELECT * FROM platform_plugin_artifact"))
                    .isEqualTo(before);
            assertThat(approval()).isEqualTo(beforeRevision);
            assertThat(revision(runtime)).isEqualTo(beforeRevision);
            assertThat(artifacts(runtime)).containsOnlyKeys("sample");
            var registry = (ModuleRegistry) field(runtime, "registry");
            assertThat(registry.moduleIds()).doesNotContain("publication-probe");
            assertThat(registry.isPlugin("publication-probe")).isFalse();
            awaitRuntimeReady(runtime);
        } finally {
            runtime.close();
        }
    }

    @Test
    void failedDisableKeepsTheStoredFlagAndVersionWithItsApproval(@TempDir Path directory)
            throws Exception {
        var runtime = newRuntime(directory);
        try {
            prepareConfiguredDependencyFixture(runtime, directory);
            var registry = (ModuleRegistry) field(runtime, "registry");
            registry.registerPlugin(new PlatformModule("sample", "Sample", "1.0.0", List.of()));
            registry.live("sample", true);
            setField(
                    runtime,
                    "states",
                    new ModuleStateStore(
                            JdbcClient.create(dataSource), registry, mock(AuditPort.class)));
            var before = jdbc.queryForList("SELECT * FROM platform_module_state");
            String beforeRevision = approval();
            rejectApproval();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    runtime.change(
                                            "fixture-admin", "sample", false, 1, "fixture change"))
                    .isInstanceOf(Exception.class);
            assertThat(jdbc.queryForList("SELECT * FROM platform_module_state")).isEqualTo(before);
            assertThat(approval()).isEqualTo(beforeRevision);
            assertThat(revision(runtime)).isEqualTo(beforeRevision);
            awaitRuntimeReady(runtime);
        } finally {
            runtime.close();
        }
    }

    private void rejectApproval() {
        jdbc.execute(
                """
                CREATE FUNCTION reject_approval() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected graph commit failure'; END $$;
                CREATE TRIGGER reject_approval BEFORE UPDATE ON platform_plugin_graph_approval
                FOR EACH ROW EXECUTE FUNCTION reject_approval();
                """);
    }

    @Test
    void heartbeatCannotReviveAnOperationThatExpiresAfterItsRecoveryScan(@TempDir Path directory)
            throws Exception {
        var runtime = newRuntime(directory);
        try {
            prepareConfiguredDependencyFixture(runtime, directory);
            UUID operation = insertRunningOperation(runtime);
            jdbc.execute(
                    """
                    CREATE FUNCTION expire_operation_during_heartbeat() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      UPDATE platform_plugin_operation SET lease_expires_at=clock_timestamp()-interval '1 second'
                        WHERE status='RUNNING';
                      RETURN NEW;
                    END $$;
                    CREATE TRIGGER expire_operation BEFORE UPDATE ON platform_plugin_runtime_node
                    FOR EACH ROW EXECUTE FUNCTION expire_operation_during_heartbeat();
                    """);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    runtime, "heartbeatTick");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT lease_expires_at<=clock_timestamp() FROM platform_plugin_operation WHERE id=?",
                                    Boolean.class,
                                    operation))
                    .isTrue();
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    runtime, "heartbeatTick");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM platform_plugin_operation WHERE id=?",
                                    String.class,
                                    operation))
                    .isEqualTo("FAILED");
        } finally {
            runtime.close();
        }
    }

    @Test
    void completionCannotUseTransactionStartTimeToAcceptAnExpiredLease(@TempDir Path directory)
            throws Exception {
        var runtime = newRuntime(directory);
        try {
            prepareConfiguredDependencyFixture(runtime, directory);
            UUID operation = insertRunningOperation(runtime);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    tx.executeWithoutResult(
                                            status -> {
                                                jdbc.update(
                                                        "UPDATE platform_plugin_operation SET lease_expires_at=clock_timestamp()+interval '150 milliseconds' WHERE id=?",
                                                        operation);
                                                while (Boolean.TRUE.equals(
                                                        jdbc.queryForObject(
                                                                "SELECT lease_expires_at>clock_timestamp() FROM platform_plugin_operation WHERE id=?",
                                                                Boolean.class,
                                                                operation))) {
                                                    try {
                                                        Thread.sleep(10);
                                                    } catch (InterruptedException error) {
                                                        Thread.currentThread().interrupt();
                                                        throw new IllegalStateException(error);
                                                    }
                                                }
                                                org.springframework.test.util.ReflectionTestUtils
                                                        .invokeMethod(
                                                                runtime,
                                                                "step",
                                                                operation,
                                                                "SUCCEEDED",
                                                                "complete",
                                                                null);
                                            }))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("pluginOperationLeaseLost");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM platform_plugin_operation WHERE id=?",
                                    String.class,
                                    operation))
                    .isEqualTo("RUNNING");
        } finally {
            runtime.close();
        }
    }

    private UUID insertRunningOperation(PluginRuntime runtime) throws Exception {
        UUID operation = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform_plugin_operation(id,module_id,kind,status,step,data_mode,owner_node_id,lease_expires_at) VALUES(?,'sample','UNINSTALL','RUNNING','fixture','PRESERVE',?,clock_timestamp()+interval '45 seconds')",
                operation,
                field(runtime, "nodeId"));
        return operation;
    }

    @Test
    void failedPreserveCommitRetainsTheEntireApprovedInstallationAndCanBeRetried(
            @TempDir Path directory) throws Exception {
        var runtime = newRuntime(directory);
        try {
            prepareConfiguredDependencyFixture(runtime, directory);
            var beforeInstallation =
                    jdbc.queryForList("SELECT * FROM platform_plugin_installation");
            var beforeState = jdbc.queryForList("SELECT * FROM platform_module_state");
            String beforeRevision = approval();
            jdbc.execute(
                    """
                    CREATE FUNCTION reject_approval() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'injected graph commit failure'; END $$;
                    CREATE TRIGGER reject_approval BEFORE UPDATE ON platform_plugin_graph_approval
                    FOR EACH ROW EXECUTE FUNCTION reject_approval();
                    """);
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
            assertThat(jdbc.queryForList("SELECT * FROM platform_plugin_installation"))
                    .isEqualTo(beforeInstallation);
            assertThat(jdbc.queryForList("SELECT * FROM platform_module_state"))
                    .isEqualTo(beforeState);
            assertThat(approval()).isEqualTo(beforeRevision);
            assertThat(revision(runtime)).isEqualTo(beforeRevision);
            awaitRuntimeReady(runtime);
            jdbc.execute("DROP TRIGGER reject_approval ON platform_plugin_graph_approval");
            var retry = runtime.plan("fixture-admin", "sample", "UNINSTALL", "PRESERVE");
            assertThat(
                            awaitTerminal(
                                            runtime,
                                            "fixture-admin",
                                            runtime.execute(
                                                    "fixture-admin",
                                                    "sample",
                                                    "UNINSTALL",
                                                    new PluginRuntime.Execute(
                                                            retry.revision(), "PRESERVE", null)))
                                    .status())
                    .isEqualTo("SUCCEEDED");
            awaitRuntimeReady(runtime);
        } finally {
            runtime.close();
        }
    }

    private void prepareConfiguredDependencyFixture(PluginRuntime runtime, Path directory)
            throws Exception {
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
                .put("sample", new PluginArchive.Artifact("a".repeat(64), manifest, directory));
        jdbc.update("UPDATE platform_module_state SET enabled=true WHERE module_id='sample'");
        String revision = revision(runtime);
        jdbc.update(
                "UPDATE platform_plugin_graph_approval SET graph_revision=? WHERE id=1", revision);
        markRuntimeReady(runtime, revision, true);
    }

    @Test
    void uninstallPlanReportsStoredReferencesAlongsideInstalledDependents(@TempDir Path directory)
            throws Exception {
        var referenced = new java.util.concurrent.atomic.AtomicBoolean(true);
        businessReferences =
                id ->
                        id.equals("sample") && referenced.get()
                                ? List.of("dictionaryReferenced:settings")
                                : List.of();
        var runtime = newRuntime(directory);
        try {
            var consumer =
                    new PluginManifest(
                            1,
                            "consumer",
                            "Consumer",
                            "1.0.0",
                            "1",
                            "com.example.ConsumerPlugin",
                            "backend/plugin.jar",
                            "frontend/plugin.js",
                            List.of(new PluginManifest.Dependency("sample", "1.0.0")),
                            List.of(),
                            List.of(),
                            List.of(),
                            Map.of(),
                            List.of());
            artifacts(runtime)
                    .put(
                            "consumer",
                            new PluginArchive.Artifact("b".repeat(64), consumer, directory));
            jdbc.update(
                    "INSERT INTO platform_plugin_artifact(module_id,version,digest,manifest) VALUES('consumer','1.0.0',?,'{}'::jsonb)",
                    "b".repeat(64));
            jdbc.update(
                    "INSERT INTO platform_plugin_installation(module_id,digest,installed,explicit_install) VALUES('consumer',?,true,true)",
                    "b".repeat(64));
            jdbc.update(
                    "INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(?,'consumer',true,1)",
                    UUID.randomUUID());
            prepareConfiguredDependencyFixture(runtime, directory);
            var blocked = runtime.plan("fixture-admin", "sample", "UNINSTALL");
            assertThat(blocked.blockers())
                    .containsExactlyInAnyOrder(
                            "pluginRequiredBy:consumer", "dictionaryReferenced:settings");
            assertThat(blocked.remove()).isEmpty();
            var failure =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            org.springframework.web.server.ResponseStatusException.class,
                            () ->
                                    runtime.execute(
                                            "fixture-admin",
                                            "sample",
                                            "UNINSTALL",
                                            new PluginRuntime.Execute(
                                                    blocked.revision(), "PRESERVE", null)));
            assertThat(failure.getReason()).isEqualTo("pluginRequiredBy:consumer");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM platform_plugin_operation", Long.class))
                    .isZero();
            referenced.set(false);
            var detached = runtime.plan("fixture-admin", "sample", "UNINSTALL");
            assertThat(detached.blockers()).containsExactly("pluginRequiredBy:consumer");
            assertThat(detached.revision()).isNotEqualTo(blocked.revision());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM platform_plugin_installation WHERE installed",
                                    Long.class))
                    .isEqualTo(2);
        } finally {
            runtime.close();
        }
    }

    @Test
    void changedBusinessReferencesInvalidateOldPlansAndPreserveInstallation(@TempDir Path directory)
            throws Exception {
        var blocked = new java.util.concurrent.atomic.AtomicBoolean();
        businessReferences =
                id -> blocked.get() ? List.of("dictionaryReferenced:settings") : List.of();
        var runtime = newRuntime(directory);
        try {
            prepareConfiguredDependencyFixture(runtime, directory);
            var initial = runtime.plan("fixture-admin", "sample", "UNINSTALL");
            assertThat(initial.blockers()).isEmpty();
            blocked.set(true);
            var changed = runtime.plan("fixture-admin", "sample", "UNINSTALL");
            assertThat(changed.blockers()).containsExactly("dictionaryReferenced:settings");
            assertThat(changed.revision()).isNotEqualTo(initial.revision());
            var stale =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            org.springframework.web.server.ResponseStatusException.class,
                            () ->
                                    runtime.execute(
                                            "fixture-admin",
                                            "sample",
                                            "UNINSTALL",
                                            new PluginRuntime.Execute(
                                                    initial.revision(), "PRESERVE", null)));
            assertThat(stale.getReason()).isEqualTo("staleRecord");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT installed FROM platform_plugin_installation WHERE module_id='sample'",
                                    Boolean.class))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM platform_plugin_operation", Long.class))
                    .isZero();
            blocked.set(false);
            assertThat(runtime.plan("fixture-admin", "sample", "UNINSTALL").revision())
                    .isEqualTo(initial.revision());
        } finally {
            runtime.close();
        }
    }

    @Test
    void referenceCommittedDuringDrainRejectsUninstallBeforeChangingInstallation(
            @TempDir Path directory) throws Exception {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        businessReferences =
                id ->
                        reads.incrementAndGet() >= 4
                                ? List.of("dictionaryReferenced:settings")
                                : List.of();
        var runtime = newRuntime(directory);
        try {
            prepareConfiguredDependencyFixture(runtime, directory);
            var plan = runtime.plan("fixture-admin", "sample", "UNINSTALL");
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
            assertThat(result.errorCode()).isEqualTo("staleRecord");
            assertThat(reads.get()).isGreaterThanOrEqualTo(4);
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
            awaitRuntimeReady(runtime);
        } finally {
            runtime.close();
        }
    }

    @SuppressWarnings("unchecked")
    private PluginRuntime newRuntime(Path root) {
        var referenceBeans =
                new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        referenceBeans.registerSingleton(
                "configuredReferences",
                (com.lumira.platform.common.PluginDependenciesPort)
                        id -> businessReferences.apply(id));
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
                "",
                dataSource,
                false,
                root.resolve("approved-graph.json").toString(),
                referenceBeans.getBeanProvider(
                        com.lumira.platform.common.PluginDependenciesPort.class));
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
