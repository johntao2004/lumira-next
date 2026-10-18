package com.lumira.platform.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AiModelInputSettingsPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.SecretStorePort;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class AiModelInputPolicyTest {
    @Test
    void officialFlashContractDoesNotEnableImagesForEveryDeepSeekModel() {
        assertTrue(
                AiModelInputPolicy.defaults(
                                AiProviderProfile.ProviderKind.DEEPSEEK, "deepseek-flash", false)
                        .contains("png"));
        assertFalse(
                AiModelInputPolicy.defaults(
                                AiProviderProfile.ProviderKind.DEEPSEEK, "deepseek-reasoner", false)
                        .contains("png"));
    }

    @Test
    void rejectsUnknownModelsFormatsDuplicatesAndNullsAndRetainsEmptyOverride() {
        assertEquals(
                Map.of("m", List.of()),
                AiModelInputPolicy.normalize(Map.of("m", List.of()), List.of("m")));
        for (var invalid :
                List.of(
                        Map.of("other", List.of("png")),
                        Map.of("m", List.of("exe")),
                        Map.of("m", List.of("png", "png")),
                        Map.of("m", Arrays.asList((String) null)))) {
            assertThrows(
                    ResponseStatusException.class,
                    () -> AiModelInputPolicy.normalize(invalid, List.of("m")));
        }
    }

    @Test
    void consoleUsesPersistedPerModelOverridesWithoutProbingAndWithoutEnablingTools() {
        var id = UUID.randomUUID();
        var repository = mock(AiProviderRepository.class);
        var connections = mock(ModelConnectionService.class);
        var settings = mock(AiModelInputSettingsPort.class);
        when(settings.read(id)).thenReturn(Map.of("first", List.of("png"), "second", List.of()));
        when(repository.findForConsole(id))
                .thenReturn(
                        Optional.of(
                                new AiProviderRepository.ConsoleProfile(
                                        id,
                                        "Fixture",
                                        AiProviderProfile.ProviderKind.CUSTOM_OPENAI,
                                        "first,second",
                                        List.of("CHAT"),
                                        true)));
        var service =
                new AiProviderService(
                        repository,
                        connections,
                        mock(SecretStorePort.class),
                        mock(AdministrationPort.class),
                        mock(AuditPort.class),
                        new ObjectMapper(),
                        settings);
        var models = service.configuredModelsForConsole(id, "actor").models();
        assertEquals(List.of("png"), models.getFirst().supportedFileFormats());
        assertTrue(models.getFirst().inputModalities().contains("image"));
        assertEquals(List.of("CHAT"), models.getFirst().capabilityCodes());
        assertEquals(List.of(), models.get(1).supportedFileFormats());
        assertFalse(models.get(1).inputModalities().contains("image"));
        verifyNoInteractions(connections);
    }
}
