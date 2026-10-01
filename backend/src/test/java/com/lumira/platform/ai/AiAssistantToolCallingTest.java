package com.lumira.platform.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.ai.AiProviderProfile.ProviderKind;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AiUserDirectoryPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class AiAssistantToolCallingTest {
    private final AiProviderService providers = mock(AiProviderService.class);
    private final ModelConnectionService connections = mock(ModelConnectionService.class);
    private final PendingAiActionStore actions = mock(PendingAiActionStore.class);
    private final AiUserDirectoryPort users = mock(AiUserDirectoryPort.class);
    private final AdministrationPort guard = mock(AdministrationPort.class);
    private final AiAssistantService service =
            new AiAssistantService(
                    providers, connections, actions, users, guard, new ObjectMapper());
    private final AiProviderProfile provider =
            new AiProviderProfile(
                    UUID.randomUUID(),
                    "DeepSeek",
                    ProviderKind.DEEPSEEK,
                    "https://api.deepseek.com",
                    "deepseek-flash",
                    List.of("VISION"),
                    true,
                    1);

    private AiAssistantService.ChatRequest request(String model) {
        when(providers.requireEnabled(provider.id(), "actor")).thenReturn(provider);
        return new AiAssistantService.ChatRequest(
                provider.id(), model, List.of(new AiAssistantService.ChatMessage("user", "测试")));
    }

    private void reply(String name, String arguments) {
        when(connections.chat(eq(provider), anyString(), anyList(), anyList()))
                .thenReturn(
                        new ModelConnectionService.ChatReply(
                                "",
                                List.of(
                                        new ModelConnectionService.ToolCall(
                                                "call-1", name, arguments))));
    }

    @Test
    void searchUsesDirectoryAndReportsBackendCompletion() {
        reply("users_search", "{\"search\":\"fixture\"}");
        var user =
                new AiUserDirectoryPort.UserView(
                        UUID.randomUUID(), "fixture", "Fixture", true, 2, null, null);
        when(users.list(new PageRequest(1, 10, "fixture"), "actor"))
                .thenReturn(new PageResult<>(List.of(user), 1, 1, 10));
        var result = service.chat(request("deepseek-flash"), "actor");
        assertEquals(List.of(user), result.users());
        assertEquals(
                List.of(
                        new AiAssistantService.ExecutionStep("MODEL_RESPONSE", "success"),
                        new AiAssistantService.ExecutionStep("USER_SEARCH", "success")),
                result.steps());
        verify(connections)
                .chat(
                        eq(provider),
                        eq("deepseek-flash"),
                        anyList(),
                        argThat(tools -> tools.size() == 1));
        verifyNoInteractions(actions);
    }

    @Test
    void createOnlyProposesAndWaitsForExplicitConfirmation() {
        when(guard.hasGlobal("actor", "platform:users:write")).thenReturn(true);
        reply("users_propose_create", "{\"username\":\"fixture\",\"displayName\":\"Fixture\"}");
        var proposal =
                new PendingAiActionStore.Proposal(
                        UUID.randomUUID(),
                        PendingAiActionStore.Kind.CREATE_USER,
                        "fixture",
                        "Fixture",
                        null,
                        Instant.now().plusSeconds(180));
        when(actions.create(
                        "actor",
                        PendingAiActionStore.Kind.CREATE_USER,
                        null,
                        0,
                        "fixture",
                        "Fixture",
                        null))
                .thenReturn(proposal);
        var result = service.chat(request("deepseek-v4-pro"), "actor");
        assertEquals(proposal, result.proposal());
        assertEquals(
                new AiAssistantService.ExecutionStep("ACTION_CONFIRMATION", "pending"),
                result.steps().getLast());
        verify(connections)
                .chat(
                        eq(provider),
                        eq("deepseek-v4-pro"),
                        anyList(),
                        argThat(tools -> tools.size() == 4));
        verifyNoInteractions(users);
    }

    @Test
    void readOnlyActorCannotExecuteForgedWriteTool() {
        reply("users_propose_create", "{\"username\":\"fixture\",\"displayName\":\"Fixture\"}");
        var result = service.chat(request("deepseek-flash"), "actor");
        assertNull(result.proposal());
        assertEquals("error", result.steps().getLast().status());
        verifyNoInteractions(actions, users);
    }

    @Test
    void unknownSelectedModelCannotInheritToolsOrExecuteInventedCall() {
        reply("users_search", "{\"search\":\"fixture\"}");
        var result = service.chat(request("unknown-model"), "actor");
        assertNull(result.users());
        assertEquals("TOOL_VALIDATION", result.steps().getLast().code());
        verify(connections).chat(eq(provider), eq("unknown-model"), anyList(), eq(List.of()));
        verifyNoInteractions(users, actions);
        var unknown =
                new AiProviderProfile(
                        provider.id(),
                        "Unknown",
                        ProviderKind.DEEPSEEK,
                        provider.baseUrl(),
                        "unknown-model",
                        List.of("CHAT", "TOOL_CALLING"),
                        true,
                        1);
        assertEquals(List.of("CHAT"), unknown.capabilityCodes());
    }

    @Test
    void malformedArgumentsAndMultipleCallsNeverExecute() {
        reply("users_search", "[]");
        assertEquals(
                "error",
                service.chat(request("deepseek-flash"), "actor").steps().getLast().status());
        when(connections.chat(eq(provider), anyString(), anyList(), anyList()))
                .thenReturn(
                        new ModelConnectionService.ChatReply(
                                "",
                                List.of(
                                        new ModelConnectionService.ToolCall(
                                                "call", "users_search", "{}"),
                                        new ModelConnectionService.ToolCall(
                                                "call", "users_search", "{}"))));
        assertEquals(
                "error",
                service.chat(request("deepseek-flash"), "actor").steps().getLast().status());
        verifyNoInteractions(users, actions);
    }

    @Test
    void deniedReadPermissionStopsBeforeModelRequest() {
        var input = request("deepseek-flash");
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(guard)
                .require("actor", "platform:users:read");
        assertThrows(ResponseStatusException.class, () -> service.chat(input, "actor"));
        verifyNoInteractions(connections, actions, users);
    }

    @Test
    void confirmationRechecksGlobalWriteAndPreservesActorAndVersion() {
        var id = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(actions.take(id, "actor"))
                .thenReturn(
                        new PendingAiActionStore.PendingAction(
                                id,
                                "actor",
                                PendingAiActionStore.Kind.UPDATE_USER,
                                userId,
                                7,
                                null,
                                "Updated",
                                false,
                                Instant.now().plusSeconds(180)));
        service.confirm(id, new AiAssistantService.ConfirmAction(null), "actor");
        verify(guard).require("actor", "platform:users:write");
        verify(guard).requireGlobal("actor", "platform:users:write");
        verify(users)
                .update(userId, new AiUserDirectoryPort.UpdateUser("Updated", false, 7L), "actor");
    }

    @Test
    void deniedConfirmationCannotConsumePendingAction() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(guard)
                .requireGlobal("actor", "platform:users:write");
        assertThrows(
                ResponseStatusException.class,
                () -> service.confirm(UUID.randomUUID(), null, "actor"));
        verifyNoInteractions(actions, users);
    }

    @Test
    void cancellationIsActorBoundAndCannotRunAUserMutation() {
        UUID id = UUID.randomUUID();
        service.cancel(id, "actor");
        verify(guard).require("actor", "platform:users:read");
        verify(actions).cancel(id, "actor");
        verifyNoInteractions(users, connections);
    }
}
