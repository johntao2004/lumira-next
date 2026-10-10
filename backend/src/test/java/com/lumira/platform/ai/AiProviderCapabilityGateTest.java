package com.lumira.platform.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class AiProviderCapabilityGateTest {
    @Test
    void legacyStoredChatFlagCannotTurnJevIntoChat() {
        var repository = mock(AiProviderRepository.class);
        var connections = mock(ModelConnectionService.class);
        var secrets = mock(SecretStorePort.class);
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);
        var id = UUID.randomUUID();
        var jev =
                new AiProviderProfile(
                        id,
                        "Jev",
                        ProviderKind.TYPESAFE_JEV,
                        "https://api.typesafe.ai",
                        "jev-latest",
                        List.of("CHAT", "VISION"),
                        true,
                        1);
        when(repository.find(id)).thenReturn(Optional.of(jev));
        var service =
                new AiProviderService(
                        repository, connections, secrets, guard, audit, new ObjectMapper());

        ResponseStatusException failure =
                assertThrows(ResponseStatusException.class, () -> service.models(id, "actor"));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        assertEquals("aiChatUnsupported", failure.getReason());
        verify(connections, never()).probe(any(AiProviderProfile.class));
    }

    @Test
    void legacyStoredFlagsCannotRemoveAdapterDeclaredChatSupport() {
        var repository = mock(AiProviderRepository.class);
        var connections = mock(ModelConnectionService.class);
        var secrets = mock(SecretStorePort.class);
        var guard = mock(AdministrationPort.class);
        var audit = mock(AuditPort.class);
        var id = UUID.randomUUID();
        var deepSeek =
                new AiProviderProfile(
                        id,
                        "DeepSeek",
                        ProviderKind.DEEPSEEK,
                        "https://api.deepseek.com",
                        "deepseek-flash",
                        List.of("VISION"),
                        true,
                        1);
        when(repository.find(id)).thenReturn(Optional.of(deepSeek));
        var result = new ModelConnectionService.ConnectionResult(List.of(), false);
        when(connections.probe(deepSeek)).thenReturn(result);
        var service =
                new AiProviderService(
                        repository, connections, secrets, guard, audit, new ObjectMapper());

        assertEquals(result, service.models(id, "actor"));
        verify(connections).probe(deepSeek);
    }
}
