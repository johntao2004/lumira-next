package com.lumira.platform.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.ai.AiProviderProfile.ProviderKind;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.SecretStorePort;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class AiConnectionSnapshotTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private DriverManagerDataSource dataSource;
    private DataSourceTransactionManager manager;
    private AiProviderRepository repository;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setup() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_ai_snapshot_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        jdbc.execute(
                "CREATE TABLE platform_ai_provider(id uuid PRIMARY KEY,name text,provider_kind text,base_url text,model_name text,capability_codes jsonb,enabled boolean,version bigint,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),created_by text,updated_by text)");
        jdbc.execute("CREATE TABLE fixture_secret(value text)");
        jdbc.update(
                "INSERT INTO platform_ai_provider(id,name,provider_kind,base_url,model_name,capability_codes,enabled,version) VALUES(?,'Fixture','OPENAI','https://api.openai.com/v1','fixture','[\"CHAT\"]',true,1)",
                id);
        jdbc.update("INSERT INTO fixture_secret VALUES('synthetic-old-key')");
        repository = new AiProviderRepository(JdbcClient.create(dataSource), new ObjectMapper());
    }

    @Test
    void providerWritePersistsOrderedModelsAndConsoleReadsTheSameChoices() {
        var secrets = mock(SecretStorePort.class);
        when(secrets.state(anyString())).thenReturn(new SecretStorePort.State(0, false));
        var connections = mock(ModelConnectionService.class);
        var service =
                new AiProviderService(
                        repository,
                        connections,
                        secrets,
                        mock(AdministrationPort.class),
                        mock(AuditPort.class),
                        new ObjectMapper(),
                        org.mockito.Mockito.mock(
                                com.lumira.platform.common.AiModelInputSettingsPort.class));
        var input =
                new AiProviderService.ProviderWrite(
                        "Local fixture",
                        ProviderKind.OLLAMA,
                        "http://127.0.0.1:11434/v1",
                        "llama-default",
                        List.of("llama-default", "llama-fallback"),
                        List.of("CHAT"),
                        true,
                        null,
                        null);

        var created = service.create(input, "qa-operator");
        var provider =
                service.list("qa-operator").stream()
                        .filter(value -> value.id().equals(created.id()))
                        .findFirst()
                        .orElseThrow();
        var console =
                service.listForConsole("qa-operator").stream()
                        .filter(value -> value.id().equals(created.id()))
                        .findFirst()
                        .orElseThrow();
        var configuredModels = service.configuredModelsForConsole(created.id(), "qa-operator");

        assertEquals(
                "llama-default,llama-fallback",
                jdbc.queryForObject(
                        "SELECT model_name FROM platform_ai_provider WHERE id=?",
                        String.class,
                        created.id()));
        assertEquals("llama-default", provider.modelName());
        assertEquals(List.of("llama-default", "llama-fallback"), provider.modelNames());
        assertEquals("llama-default", console.modelName());
        assertEquals(List.of("llama-default", "llama-fallback"), console.modelNames());
        assertEquals(
                List.of("llama-default", "llama-fallback"),
                configuredModels.models().stream()
                        .map(ModelConnectionService.ModelOption::id)
                        .toList());
        assertTrue(configuredModels.configuredModelAvailable());
        verifyNoInteractions(connections);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void profileAndSecretShareOneRepeatableReadSnapshotAndLeaveNoTransaction() throws Exception {
        AiProviderProfile requested = repository.find(id).orElseThrow();
        SecretStorePort secrets = mock(SecretStorePort.class);
        when(secrets.reveal(ModelConnectionService.secretContext(id)))
                .thenAnswer(
                        call -> {
                            assertTrue(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            try (var writer =
                                    java.util.concurrent.Executors.newSingleThreadExecutor()) {
                                writer.submit(
                                                () ->
                                                        new TransactionTemplate(manager)
                                                                .executeWithoutResult(
                                                                        status -> {
                                                                            jdbc.update(
                                                                                    "UPDATE platform_ai_provider SET base_url='https://api.deepseek.com',version=2 WHERE id=?",
                                                                                    id);
                                                                            jdbc.update(
                                                                                    "UPDATE fixture_secret SET value='synthetic-new-key'");
                                                                        }))
                                        .get(3, java.util.concurrent.TimeUnit.SECONDS);
                            }
                            return Optional.of(
                                    jdbc.queryForObject(
                                                    "SELECT value FROM fixture_secret",
                                                    String.class)
                                            .getBytes(StandardCharsets.UTF_8));
                        });
        AiConnectionSnapshot snapshots = new AiConnectionSnapshot(repository, secrets, manager);

        byte[] capturedBytes;
        byte[] ownedBytes;
        AiProviderProfile capturedProfile;
        try (AiConnectionSnapshot.Captured captured = snapshots.capture(requested)) {
            ownedBytes = captured.credential();
            capturedBytes = Arrays.copyOf(ownedBytes, ownedBytes.length);
            capturedProfile = captured.profile();
            assertEquals("synthetic-old-key", new String(capturedBytes, StandardCharsets.UTF_8));
            assertEquals("https://api.openai.com/v1", capturedProfile.baseUrl());
        }

        assertArrayEquals(new byte[ownedBytes.length], ownedBytes);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
        AiProviderProfile current = repository.find(id).orElseThrow();
        assertEquals("https://api.deepseek.com", current.baseUrl());
        assertEquals(
                "synthetic-new-key",
                jdbc.queryForObject("SELECT value FROM fixture_secret", String.class));
        var stale = assertThrows(ResponseStatusException.class, () -> snapshots.capture(requested));
        assertEquals("staleRecord", stale.getReason());
        verify(secrets, times(1)).reveal(ModelConnectionService.secretContext(id));
        Arrays.fill(capturedBytes, (byte) 0);
    }

    @Test
    void unsavedDiscoveryUsesOnlyItsSuppliedCredentialAndOwnsAClearedCopy() {
        SecretStorePort secrets = mock(SecretStorePort.class);
        AiConnectionSnapshot snapshots = new AiConnectionSnapshot(repository, secrets, manager);
        AiProviderProfile unsaved =
                new AiProviderProfile(
                        UUID.randomUUID(),
                        "Discovery",
                        ProviderKind.OPENAI,
                        "https://api.openai.com/v1",
                        "",
                        List.of("CHAT"),
                        true,
                        1);
        byte[] override = "synthetic-discovery-key".getBytes(StandardCharsets.UTF_8);

        byte[] captured;
        try (AiConnectionSnapshot.Captured snapshot = snapshots.capture(unsaved, override, true)) {
            captured = snapshot.credential();
            assertNotSame(override, captured);
            assertArrayEquals(override, captured);
        }

        assertArrayEquals(new byte[captured.length], captured);
        assertEquals("synthetic-discovery-key", new String(override, StandardCharsets.UTF_8));
        verifyNoInteractions(secrets);
        Arrays.fill(override, (byte) 0);
    }

    @Test
    void aMissingSavedProviderCannotFallBackToItsPreviouslyReadDestination() {
        AiProviderProfile requested = repository.find(id).orElseThrow();
        jdbc.update("DELETE FROM platform_ai_provider WHERE id=?", id);
        SecretStorePort secrets = mock(SecretStorePort.class);
        AiConnectionSnapshot snapshots = new AiConnectionSnapshot(repository, secrets, manager);

        var stale =
                assertThrows(
                        ResponseStatusException.class,
                        () -> snapshots.capture(requested, null, true));

        assertEquals("staleRecord", stale.getReason());
        verifyNoInteractions(secrets);
    }
}
