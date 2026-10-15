package com.lumira.platform.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.ai.AiProviderProfile.ProviderKind;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.SecretStorePort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class AiProviderCredentialBindingTest {
    private static final String ACTOR = "qa-operator";
    private static final UUID PROVIDER_ID = UUID.fromString("91a54117-7f42-4ace-8db1-1575c04d7791");
    private static final String OPENAI_ORIGIN = "https://api.openai.com/v1";

    @ParameterizedTest(name = "configured credential cannot be implicitly reused for {0}")
    @MethodSource("connectionChangesWithoutReplacement")
    void updateRejectsImplicitCredentialReuseBeforePersistence(
            String scenario, AiProviderService.ProviderWrite input) {
        var fixture = fixture(provider(ProviderKind.OPENAI, OPENAI_ORIGIN), true, 7);

        ResponseStatusException failure =
                assertThrows(
                        ResponseStatusException.class,
                        () -> fixture.service().update(PROVIDER_ID, input, ACTOR));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        assertEquals("invalidAiProvider", failure.getReason());
        verify(fixture.repository(), never()).update(any(), anyString(), anyString());
        verify(fixture.secrets(), never()).save(anyString(), anyLong(), any());
        verify(fixture.secrets(), never()).reveal(anyString());
        verify(fixture.connections(), never()).probe(any());
        verify(fixture.connections(), never()).discoverModels(any(), any());
        verify(fixture.audit(), never()).append(anyString(), anyString(), any());
        verify(fixture.secrets()).state(secretContext());
    }

    static List<Object[]> connectionChangesWithoutReplacement() {
        return List.of(
                new Object[] {
                    "a changed base URL with omitted credential action",
                    write(ProviderKind.OPENAI, "https://api.deepseek.com", null)
                },
                new Object[] {
                    "a changed base URL with KEEP",
                    write(
                            ProviderKind.OPENAI,
                            "https://api.deepseek.com",
                            new AiProviderService.CredentialChange("KEEP", null))
                },
                new Object[] {
                    "a changed provider kind with KEEP",
                    write(
                            ProviderKind.DEEPSEEK,
                            OPENAI_ORIGIN,
                            new AiProviderService.CredentialChange("KEEP", null))
                },
                new Object[] {
                    "a changed base URL with an implicit KEEP action",
                    write(
                            ProviderKind.OPENAI,
                            "https://api.deepseek.com",
                            new AiProviderService.CredentialChange(null, null))
                });
    }

    @Test
    void updateAllowsModelChangesWhenNormalizedConnectionIsUnchanged() {
        var fixture = fixture(provider(ProviderKind.OPENAI, OPENAI_ORIGIN + "/"), true, 7);
        var input =
                new AiProviderService.ProviderWrite(
                        "Updated name",
                        ProviderKind.OPENAI,
                        " " + OPENAI_ORIGIN + "/// ",
                        "gpt-next",
                        List.of("VISION"),
                        true,
                        3L,
                        null);
        when(fixture.repository().update(any(), anyString(), eq(ACTOR))).thenReturn(1);

        fixture.service().update(PROVIDER_ID, input, ACTOR);

        var saved = ArgumentCaptor.forClass(AiProviderProfile.class);
        verify(fixture.repository()).update(saved.capture(), anyString(), eq(ACTOR));
        assertEquals(OPENAI_ORIGIN, saved.getValue().baseUrl());
        assertEquals("gpt-next", saved.getValue().modelName());
        verify(fixture.secrets(), never()).save(anyString(), anyLong(), any());
        verify(fixture.connections(), never()).probe(any());
    }

    @Test
    void updatePersistsOrderedModelIdsWithinTheLegacyColumnAndReturnsBothFields() {
        var fixture = fixture(provider(ProviderKind.OPENAI, OPENAI_ORIGIN), true, 7);
        var input =
                new AiProviderService.ProviderWrite(
                        "Updated name",
                        ProviderKind.OPENAI,
                        OPENAI_ORIGIN,
                        "gpt-default",
                        List.of("gpt-default", "gpt-fallback"),
                        List.of("CHAT"),
                        true,
                        3L,
                        null);
        var updated =
                new AiProviderProfile(
                        PROVIDER_ID,
                        "Updated name",
                        ProviderKind.OPENAI,
                        OPENAI_ORIGIN,
                        "gpt-default,gpt-fallback",
                        List.of("CHAT"),
                        true,
                        4);
        when(fixture.repository().update(any(), anyString(), eq(ACTOR))).thenReturn(1);
        when(fixture.repository().find(PROVIDER_ID))
                .thenReturn(Optional.of(provider(ProviderKind.OPENAI, OPENAI_ORIGIN)))
                .thenReturn(Optional.of(updated));

        var result = fixture.service().update(PROVIDER_ID, input, ACTOR);

        var saved = ArgumentCaptor.forClass(AiProviderProfile.class);
        verify(fixture.repository()).update(saved.capture(), anyString(), eq(ACTOR));
        assertEquals("gpt-default,gpt-fallback", saved.getValue().encodedModelNames());
        assertEquals(List.of("gpt-default", "gpt-fallback"), saved.getValue().modelNames());
        assertEquals("gpt-default", result.modelName());
        assertEquals(List.of("gpt-default", "gpt-fallback"), result.modelNames());
    }

    @Test
    void updateRejectsDuplicateOrOverlongEncodedModelSelectionsBeforePersistence() {
        for (List<String> models :
                List.of(
                        List.of("gpt-default", "gpt-default"),
                        List.of("a".repeat(128), "b".repeat(127), "c"))) {
            var fixture = fixture(provider(ProviderKind.OPENAI, OPENAI_ORIGIN), true, 7);
            var input =
                    new AiProviderService.ProviderWrite(
                            "Updated name",
                            ProviderKind.OPENAI,
                            OPENAI_ORIGIN,
                            models.getFirst(),
                            models,
                            List.of("CHAT"),
                            true,
                            3L,
                            null);

            ResponseStatusException failure =
                    assertThrows(
                            ResponseStatusException.class,
                            () -> fixture.service().update(PROVIDER_ID, input, ACTOR));

            assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
            assertEquals("invalidAiProvider", failure.getReason());
            verify(fixture.repository(), never()).update(any(), anyString(), anyString());
        }
    }

    @Test
    void updateRejectsClearingCredentialForCredentialRequiredProviderBeforePersistence() {
        var fixture = fixture(provider(ProviderKind.OPENAI, OPENAI_ORIGIN), true, 7);
        var input =
                write(
                        ProviderKind.OPENAI,
                        "https://api.deepseek.com",
                        new AiProviderService.CredentialChange("CLEAR", null));

        ResponseStatusException failure =
                assertThrows(
                        ResponseStatusException.class,
                        () -> fixture.service().update(PROVIDER_ID, input, ACTOR));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        assertEquals("aiProviderCredentialRequired", failure.getReason());
        verify(fixture.repository(), never()).update(any(), anyString(), anyString());
        verify(fixture.secrets(), never()).save(anyString(), anyLong(), any());
        verify(fixture.connections(), never()).probe(any());
    }

    @Test
    void updateStillRequiresCredentialWhenNoSavedSecretExists() {
        var fixture = fixture(provider(ProviderKind.OPENAI, OPENAI_ORIGIN), false, 0);
        var input = write(ProviderKind.OPENAI, "https://api.deepseek.com", null);

        ResponseStatusException failure =
                assertThrows(
                        ResponseStatusException.class,
                        () -> fixture.service().update(PROVIDER_ID, input, ACTOR));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        assertEquals("aiProviderCredentialRequired", failure.getReason());
        verify(fixture.repository(), never()).update(any(), anyString(), anyString());
        verify(fixture.secrets(), never()).save(anyString(), anyLong(), any());
    }

    @Test
    void updateAllowsChangingLocalConnectionWithoutSavedCredential() {
        var fixture =
                fixture(provider(ProviderKind.LM_STUDIO, "http://127.0.0.1:1234/v1"), false, 0);
        var input = write(ProviderKind.OLLAMA, "http://127.0.0.1:11434/v1", null);
        when(fixture.repository().update(any(), anyString(), eq(ACTOR))).thenReturn(1);

        fixture.service().update(PROVIDER_ID, input, ACTOR);

        verify(fixture.repository()).update(any(), anyString(), eq(ACTOR));
        verify(fixture.secrets(), never()).save(anyString(), anyLong(), any());
    }

    @Test
    void updateAllowsReplacingCredentialWhenConnectionChanges() {
        var fixture = fixture(provider(ProviderKind.OPENAI, OPENAI_ORIGIN), true, 7);
        var input =
                write(
                        ProviderKind.OPENAI,
                        "https://api.deepseek.com",
                        new AiProviderService.CredentialChange("REPLACE", "synthetic-test-key"));
        when(fixture.repository().update(any(), anyString(), eq(ACTOR))).thenReturn(1);

        fixture.service().update(PROVIDER_ID, input, ACTOR);

        verify(fixture.secrets()).save(eq(secretContext()), eq(7L), any(byte[].class));
        verify(fixture.repository()).update(any(), anyString(), eq(ACTOR));
    }

    @Test
    void discoveryRejectsProviderIdAtDifferentBaseUrlBeforeConnectionOrSecretAccess() {
        var fixture = fixture(provider(ProviderKind.OPENAI, OPENAI_ORIGIN), true, 7);
        var input =
                new AiProviderService.ModelDiscoveryRequest(
                        ProviderKind.OPENAI, "https://api.deepseek.com", null, PROVIDER_ID);

        ResponseStatusException failure =
                assertThrows(
                        ResponseStatusException.class,
                        () -> fixture.service().discoverModels(input, ACTOR));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        assertEquals("invalidAiProvider", failure.getReason());
        verify(fixture.connections(), never()).discoverModels(any(), any());
        verify(fixture.secrets(), never()).state(anyString());
        verify(fixture.secrets(), never()).reveal(anyString());
        verify(fixture.secrets(), never()).save(anyString(), anyLong(), any());
        verify(fixture.repository(), never()).update(any(), anyString(), anyString());
    }

    @Test
    void discoveryRejectsProviderIdWithDifferentKindEvenAtSameUrl() {
        var fixture = fixture(provider(ProviderKind.OPENAI, OPENAI_ORIGIN), true, 7);
        var input =
                new AiProviderService.ModelDiscoveryRequest(
                        ProviderKind.DEEPSEEK, OPENAI_ORIGIN, null, PROVIDER_ID);

        ResponseStatusException failure =
                assertThrows(
                        ResponseStatusException.class,
                        () -> fixture.service().discoverModels(input, ACTOR));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        verify(fixture.connections(), never()).discoverModels(any(), any());
        verify(fixture.secrets(), never()).reveal(anyString());
    }

    @Test
    void discoveryReusesSavedProviderOnlyForSameNormalizedConnection() {
        var fixture = fixture(provider(ProviderKind.OPENAI, OPENAI_ORIGIN + "/"), true, 7);
        var expected = new ModelConnectionService.ConnectionResult(List.of(), false);
        when(fixture.connections().discoverModels(any(), isNull(String.class)))
                .thenReturn(expected);
        var input =
                new AiProviderService.ModelDiscoveryRequest(
                        ProviderKind.OPENAI, " " + OPENAI_ORIGIN + "/// ", null, PROVIDER_ID);

        var actual = fixture.service().discoverModels(input, ACTOR);

        assertEquals(expected, actual);
        var transientProvider = ArgumentCaptor.forClass(AiProviderProfile.class);
        verify(fixture.connections())
                .discoverModels(transientProvider.capture(), isNull(String.class));
        assertEquals(PROVIDER_ID, transientProvider.getValue().id());
        assertEquals(ProviderKind.OPENAI, transientProvider.getValue().providerKind());
        assertEquals(OPENAI_ORIGIN, transientProvider.getValue().baseUrl());
        verify(fixture.secrets(), never()).reveal(anyString());
    }

    @Test
    void discoveryAllowsCredentialOverrideAtSameNormalizedConnection() {
        var fixture = fixture(provider(ProviderKind.OPENAI, OPENAI_ORIGIN), true, 7);
        var expected = new ModelConnectionService.ConnectionResult(List.of(), false);
        when(fixture.connections().discoverModels(any(), eq("synthetic-override")))
                .thenReturn(expected);

        assertEquals(
                expected,
                fixture.service()
                        .discoverModels(
                                new AiProviderService.ModelDiscoveryRequest(
                                        ProviderKind.OPENAI,
                                        OPENAI_ORIGIN + "/",
                                        "synthetic-override",
                                        PROVIDER_ID),
                                ACTOR));

        var transientProvider = ArgumentCaptor.forClass(AiProviderProfile.class);
        verify(fixture.connections())
                .discoverModels(transientProvider.capture(), eq("synthetic-override"));
        assertEquals(PROVIDER_ID, transientProvider.getValue().id());
    }

    private static Fixture fixture(
            AiProviderProfile existing, boolean credentialConfigured, long secretVersion) {
        var repository = mock(AiProviderRepository.class);
        var connections = mock(ModelConnectionService.class);
        var secrets = mock(SecretStorePort.class);
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);
        when(repository.find(existing.id())).thenReturn(Optional.of(existing));
        when(secrets.state(secretContext()))
                .thenReturn(new SecretStorePort.State(secretVersion, credentialConfigured));
        var service =
                new AiProviderService(
                        repository, connections, secrets, guard, audit, new ObjectMapper());
        return new Fixture(repository, connections, secrets, guard, audit, service);
    }

    private static AiProviderProfile provider(ProviderKind kind, String baseUrl) {
        return new AiProviderProfile(
                PROVIDER_ID, "Fixture", kind, baseUrl, "fixture-model", List.of("CHAT"), true, 3);
    }

    private static String secretContext() {
        return ModelConnectionService.secretContext(PROVIDER_ID);
    }

    private static AiProviderService.ProviderWrite write(
            ProviderKind kind, String baseUrl, AiProviderService.CredentialChange credential) {
        return new AiProviderService.ProviderWrite(
                "Fixture updated",
                kind,
                baseUrl,
                kind == ProviderKind.DEEPSEEK ? "deepseek-chat" : "gpt-next",
                List.of("CHAT"),
                true,
                3L,
                credential);
    }

    private record Fixture(
            AiProviderRepository repository,
            ModelConnectionService connections,
            SecretStorePort secrets,
            AdministrationPort guard,
            AuditPort audit,
            AiProviderService service) {}
}
