package com.lumira.platform.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lumira.platform.ai.AiProviderProfile.ProviderKind;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.platform.common.SecretStorePort;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AiProviderConsoleLookupTest {
    @Test
    void providerListUsesConsoleAccessAndExcludesConnectionSecrets() {
        var repository = mock(AiProviderRepository.class);
        var connections = mock(ModelConnectionService.class);
        var guard = mock(AdministrationPort.class);
        var id = UUID.randomUUID();
        when(repository.findEnabledForConsole())
                .thenReturn(
                        List.of(
                                new AiProviderRepository.ConsoleProfile(
                                        id,
                                        "Chat provider",
                                        ProviderKind.DEEPSEEK,
                                        "deepseek-flash",
                                        List.of("CHAT"),
                                        true)));
        var service = service(repository, connections, guard);

        var result = service.listForConsole("actor");

        assertEquals(1, result.size());
        assertEquals(id, result.getFirst().id());
        assertEquals(List.of("CHAT", "TOOL_CALLING"), result.getFirst().capabilityCodes());
        Set<String> fields =
                java.util.Arrays.stream(
                                AiProviderService.ConsoleProviderView.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .collect(java.util.stream.Collectors.toSet());
        assertFalse(fields.contains("baseUrl"));
        assertFalse(fields.contains("credentialConfigured"));
        assertFalse(fields.contains("version"));
        verify(guard).requirePage("actor", PlatformPageId.AI_CONSOLE);
        verifyNoInteractions(connections);
    }

    @Test
    void modelLookupReturnsOnlyConfiguredModelAndNeverProbesProvider() {
        var repository = mock(AiProviderRepository.class);
        var connections = mock(ModelConnectionService.class);
        var guard = mock(AdministrationPort.class);
        var id = UUID.randomUUID();
        when(repository.findForConsole(id))
                .thenReturn(
                        Optional.of(
                                new AiProviderRepository.ConsoleProfile(
                                        id,
                                        "Chat provider",
                                        ProviderKind.DEEPSEEK,
                                        "deepseek-flash",
                                        List.of("CHAT"),
                                        true)));
        var service = service(repository, connections, guard);

        var result = service.configuredModelsForConsole(id, "actor");

        assertTrue(result.configuredModelAvailable());
        assertEquals(1, result.models().size());
        assertEquals("deepseek-flash", result.models().getFirst().id());
        assertEquals(List.of("CHAT", "TOOL_CALLING"), result.models().getFirst().capabilityCodes());
        assertEquals(List.of(), result.models().getFirst().inputModalities());
        verify(guard).requirePage("actor", PlatformPageId.AI_CONSOLE);
        verifyNoInteractions(connections);
    }

    private static AiProviderService service(
            AiProviderRepository repository,
            ModelConnectionService connections,
            AdministrationPort guard) {
        return new AiProviderService(
                repository,
                connections,
                mock(SecretStorePort.class),
                guard,
                mock(AuditPort.class),
                new ObjectMapper());
    }
}
