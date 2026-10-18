package com.lumira.platform.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AiAttachmentPort;
import com.lumira.platform.common.AiUserDirectoryPort;
import com.lumira.platform.common.ContentPolicyGuard;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class AiChatAttachmentsTest {
    private final AdministrationPort guard = mock(AdministrationPort.class);
    private final AiProviderService providers = mock(AiProviderService.class);
    private final ModelConnectionService connections = mock(ModelConnectionService.class);
    private final AiAttachmentPort attachments = mock(AiAttachmentPort.class);
    private final ContentPolicyGuard policy = mock(ContentPolicyGuard.class);
    private final AiProviderProfile provider =
            new AiProviderProfile(
                    UUID.randomUUID(),
                    "model",
                    AiProviderProfile.ProviderKind.CUSTOM_OPENAI,
                    "https://example.com",
                    "vision-model",
                    List.of("VISION"),
                    true,
                    1);
    private final AiAssistantService service =
            new AiAssistantService(
                    providers,
                    connections,
                    mock(PendingAiActionStore.class),
                    mock(AiUserDirectoryPort.class),
                    guard,
                    new ObjectMapper(),
                    attachments,
                    policy);

    private AiAssistantService.ChatRequest input(List<AiAssistantService.ChatMessage> messages) {
        when(providers.requireEnabledForConsole(provider.id(), "actor")).thenReturn(provider);
        when(providers.supportedFileFormats(provider, "vision-model"))
                .thenReturn(
                        AiModelInputPolicy.defaults(provider.providerKind(), "vision-model", true));
        when(connections.chat(eq(provider), anyString(), anyList(), anyList()))
                .thenReturn(new ModelConnectionService.ChatReply("done", List.of()));
        return new AiAssistantService.ChatRequest(provider.id(), "vision-model", messages);
    }

    @Test
    void pageAccessAloneCannotStartAiChat() {
        when(guard.requireOperation(anyString(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"));

        assertThrows(
                ResponseStatusException.class,
                () ->
                        service.chat(
                                new AiAssistantService.ChatRequest(
                                        provider.id(), "vision-model", List.of()),
                                "actor"));
        verify(providers, never()).requireEnabledForConsole(any(), anyString());
        verifyNoInteractions(connections);
    }

    @Test
    void unconfiguredModelIsRejectedBeforeReadingAttachmentsOrCallingProvider() {
        var attachmentId = UUID.randomUUID();
        var messages =
                List.of(
                        new AiAssistantService.ChatMessage(
                                "user", "Summarize this file", List.of(attachmentId)));
        input(messages);
        var unconfigured =
                new AiAssistantService.ChatRequest(provider.id(), "unconfigured-model", messages);

        ResponseStatusException failure =
                assertThrows(
                        ResponseStatusException.class, () -> service.chat(unconfigured, "actor"));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        assertEquals("invalidAiChat", failure.getReason());
        verifyNoInteractions(attachments);
        verify(connections, never()).chat(any(), anyString(), anyList(), anyList());
    }

    @Test
    void textAttachmentIsExtractedServerSideAndScannedBeforeModelCall() {
        var id = UUID.randomUUID();
        when(attachments.read("actor", id))
                .thenReturn(
                        new AiAttachmentPort.Content(
                                id, "report.docx", 100, "revenue 726", null, true));
        service.chat(
                input(
                        List.of(
                                new AiAssistantService.ChatMessage(
                                        "user", "Summarize", List.of(id)))),
                "actor");
        verify(policy).requireAllowed("actor", "revenue 726", "ai-attachment");
        verify(connections)
                .chat(
                        eq(provider),
                        eq("vision-model"),
                        argThat(
                                messages ->
                                        messages.get(1)
                                                        .get("content")
                                                        .toString()
                                                        .contains("revenue 726")
                                                && messages.get(1)
                                                        .get("content")
                                                        .toString()
                                                        .contains("Attachment truncated")),
                        eq(List.of()));
        verify(connections, never()).probe(any());
    }

    @Test
    void imagesUseMultimodalPartsOnlyWhenSelectedModelConfigurationAllowsThem() {
        var id = UUID.randomUUID();
        when(attachments.read("actor", id))
                .thenReturn(
                        new AiAttachmentPort.Content(
                                id, "image.png", 100, null, "data:image/png;base64,AAAA", false));
        var request = input(List.of(new AiAssistantService.ChatMessage("user", "", List.of(id))));
        service.chat(request, "actor");
        verify(connections)
                .chat(
                        eq(provider),
                        anyString(),
                        argThat(
                                messages -> {
                                    var parts = (List<?>) messages.get(1).get("content");
                                    return parts.size() == 2
                                            && ((Map<?, ?>) parts.get(1))
                                                    .get("type")
                                                    .equals("image_url");
                                }),
                        eq(List.of()));
        clearInvocations(connections);
        when(providers.supportedFileFormats(provider, "vision-model")).thenReturn(List.of("pdf"));
        assertEquals(
                "aiAttachmentVisionRequired",
                assertThrows(ResponseStatusException.class, () -> service.chat(request, "actor"))
                        .getReason());
        verify(connections, never()).chat(any(), anyString(), anyList(), anyList());
    }

    @Test
    void excludedDocumentFormatCannotReachTheProviderEvenWithForgedAttachmentIds() {
        var id = UUID.randomUUID();
        var request =
                input(List.of(new AiAssistantService.ChatMessage("user", "Read", List.of(id))));
        when(providers.supportedFileFormats(provider, "vision-model")).thenReturn(List.of("png"));
        when(attachments.read("actor", id))
                .thenReturn(
                        new AiAttachmentPort.Content(id, "report.pdf", 100, "text", null, false));
        assertEquals(
                "aiAttachmentModelUnsupported",
                assertThrows(ResponseStatusException.class, () -> service.chat(request, "actor"))
                        .getReason());
        verifyNoInteractions(connections);
    }

    @Test
    void duplicateAndAssistantAttachmentsAreRejectedAndMissingFilesNeverReachModel() {
        var id = UUID.randomUUID();
        for (var message :
                List.of(
                        new AiAssistantService.ChatMessage("user", "test", List.of(id, id)),
                        new AiAssistantService.ChatMessage("assistant", "test", List.of(id)))) {
            assertThrows(
                    ResponseStatusException.class,
                    () -> service.chat(input(List.of(message)), "actor"));
        }
        verifyNoInteractions(attachments);
        when(attachments.read("actor", id))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "fileNotFound"));
        assertThrows(
                ResponseStatusException.class,
                () ->
                        service.chat(
                                input(
                                        List.of(
                                                new AiAssistantService.ChatMessage(
                                                        "user", "test", List.of(id)))),
                                "actor"));
        verify(connections, never()).chat(any(), anyString(), anyList(), anyList());
    }
}
