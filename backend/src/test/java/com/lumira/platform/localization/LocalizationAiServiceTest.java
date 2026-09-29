package com.lumira.platform.localization;

import com.lumira.platform.ai.AiProviderProfile;
import com.lumira.platform.ai.AiProviderProfile.ProviderKind;
import com.lumira.platform.ai.AiProviderRepository;
import com.lumira.platform.ai.ModelConnectionService;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.SecretStorePort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalizationAiServiceTest {
    @Test
    void allowsCredentialOptionalLocalChatProvidersAndStillRequiresHostedCredentials() throws Exception {
        var local = provider("Local LM Studio", ProviderKind.LM_STUDIO, "local-chat", List.of("CHAT"), true);
        var hostedWithoutCredential = provider("Hosted without credential", ProviderKind.OPENAI,
            "hosted-chat", List.of("CHAT"), true);
        var hostedWithCredential = provider("Hosted with credential", ProviderKind.OPENAI,
            "hosted-chat-configured", List.of("CHAT"), true);
        var decision = provider("Decision model", ProviderKind.TYPESAFE_JEV,
            "jev-latest", List.of("CHAT", "CHOICE"), true);

        var providers = mock(AiProviderRepository.class);
        when(providers.findAll()).thenReturn(List.of(local, hostedWithoutCredential, hostedWithCredential, decision));
        when(providers.find(local.id())).thenReturn(Optional.of(local));
        when(providers.find(hostedWithoutCredential.id())).thenReturn(Optional.of(hostedWithoutCredential));

        var secrets = mock(SecretStorePort.class);
        when(secrets.state(ModelConnectionService.secretContext(hostedWithoutCredential.id())))
            .thenReturn(new SecretStorePort.State(0, false));
        when(secrets.state(ModelConnectionService.secretContext(hostedWithCredential.id())))
            .thenReturn(new SecretStorePort.State(1, true));

        var localization = mock(LocalizationService.class);
        when(localization.bundle("en")).thenReturn(new LocalizationService.Bundle(
            1, "en", "English", "en", "en", 1, 1, Map.of("custom.ai.runtime", "Hello {name}")));
        var connections = mock(ModelConnectionService.class);
        when(connections.chat(eq(local), eq("local-chat"), anyList(), anyList()))
            .thenReturn(new ModelConnectionService.ChatReply(
                "{\"custom.ai.runtime\":\"Bonjour {name}\"}", List.of()));

        var service = new LocalizationAiService(providers, connections, secrets, localization,
            mock(AdministrationPort.class), new ObjectMapper());

        assertEquals(List.of(
            new LocalizationAiService.ProviderOption(local.id(), local.name(), local.modelName()),
            new LocalizationAiService.ProviderOption(hostedWithCredential.id(), hostedWithCredential.name(), hostedWithCredential.modelName())
        ), service.providers("admin"));

        var translated = service.translate(new LocalizationAiService.TranslationRequest(
            local.id(), "local-chat", "en", "zh-CN", List.of("custom.ai.runtime")), "admin");

        assertEquals(1, translated.succeeded());
        assertEquals(0, translated.failed());
        assertEquals(new LocalizationAiService.TranslationItem("custom.ai.runtime", "Bonjour {name}", null),
            translated.items().getFirst());
        verify(connections).chat(eq(local), eq("local-chat"), anyList(), anyList());
        verify(secrets, never()).state(ModelConnectionService.secretContext(local.id()));

        var unavailable = assertThrows(ResponseStatusException.class, () -> service.translate(
            new LocalizationAiService.TranslationRequest(hostedWithoutCredential.id(), "hosted-chat", "en", "zh-CN",
                List.of("custom.ai.runtime")), "admin"));
        assertEquals("localizationAiUnavailable", unavailable.getReason());
        verify(connections, never()).chat(eq(hostedWithoutCredential), eq("hosted-chat"), anyList(), anyList());
    }

    private static AiProviderProfile provider(String name, ProviderKind kind, String model,
            List<String> capabilities, boolean enabled) {
        return new AiProviderProfile(UUID.randomUUID(), name, kind, kind.defaultBaseUrl(), model,
            capabilities, enabled, 1);
    }
}
