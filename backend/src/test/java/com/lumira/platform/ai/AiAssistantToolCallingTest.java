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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
                    providers,
                    connections,
                    actions,
                    users,
                    guard,
                    new ObjectMapper(),
                    mock(com.lumira.platform.common.AiAttachmentPort.class),
                    mock(com.lumira.platform.common.ContentPolicyGuard.class));
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
        assertTrue(result.message().contains("| 账号 | 姓名 | 状态 | 记录版本 | 记录标识 |"));
        assertTrue(result.message().contains("| fixture | Fixture | 启用 | 2 | " + user.id()));
        assertEquals(
                List.of(
                        new AiAssistantService.ExecutionStep("MODEL_RESPONSE", "success"),
                        new AiAssistantService.ExecutionStep(
                                "USER_SEARCH", "success", "user-management", "query")),
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
                List.of(
                        new AiAssistantService.ExecutionStep("MODEL_RESPONSE", "success"),
                        new AiAssistantService.ExecutionStep(
                                "ACTION_PROPOSAL", "success", "user-management", "create"),
                        new AiAssistantService.ExecutionStep(
                                "ACTION_CONFIRMATION", "pending", "user-management", "create")),
                result.steps());
        verify(connections)
                .chat(
                        eq(provider),
                        eq("deepseek-v4-pro"),
                        anyList(),
                        argThat(tools -> tools.size() == 4));
        verifyNoInteractions(users);
    }

    @Test
    void updateAndDeleteProposalsCarryOnlyTheirUserManagementOperationCodes() {
        when(guard.hasGlobal("actor", "platform:users:write")).thenReturn(true);
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var targetUser =
                new AiUserDirectoryPort.UserView(
                        userId, "fixture-b", "Actual target B", true, 1, null, null);
        when(users.find(userId, "actor")).thenReturn(targetUser);
        when(connections.chat(eq(provider), anyString(), anyList(), anyList()))
                .thenReturn(
                        new ModelConnectionService.ChatReply(
                                "The model claims it means Alice, but the tool ID identifies B.",
                                List.of(
                                        new ModelConnectionService.ToolCall(
                                                "call-1",
                                                "users_propose_update",
                                                "{\"id\":\"00000000-0000-0000-0000-000000000001\",\"version\":1,\"displayName\":\"Updated\",\"enabled\":false}"))),
                        new ModelConnectionService.ChatReply(
                                "",
                                List.of(
                                        new ModelConnectionService.ToolCall(
                                                "call-2",
                                                "users_propose_delete",
                                                "{\"id\":\"00000000-0000-0000-0000-000000000001\",\"version\":1}"))));
        when(actions.create(
                        eq("actor"),
                        any(PendingAiActionStore.Kind.class),
                        any(UUID.class),
                        anyLong(),
                        nullable(String.class),
                        nullable(String.class),
                        nullable(Boolean.class),
                        nullable(PendingAiActionStore.Target.class)))
                .thenAnswer(
                        invocation ->
                                new PendingAiActionStore.Proposal(
                                        UUID.randomUUID(),
                                        invocation.getArgument(1),
                                        invocation.getArgument(4),
                                        invocation.getArgument(5),
                                        invocation.getArgument(6),
                                        Instant.now().plusSeconds(180),
                                        invocation.getArgument(7)));

        var update = service.chat(request("deepseek-v4-pro"), "actor");
        var delete = service.chat(request("deepseek-v4-pro"), "actor");

        assertEquals("update", update.steps().get(1).operationCode());
        assertEquals("update", update.steps().get(2).operationCode());
        assertEquals("delete", delete.steps().get(1).operationCode());
        assertEquals("delete", delete.steps().get(2).operationCode());
        assertEquals("user-management", update.steps().get(2).moduleCode());
        assertEquals("user-management", delete.steps().get(2).moduleCode());
        assertEquals(
                new PendingAiActionStore.Target(userId, "fixture-b", "Actual target B", true, 1),
                update.proposal().target());
        assertEquals(targetUser, users.find(userId, "actor"));
    }

    @Test
    void readOnlyActorCannotExecuteForgedWriteTool() {
        reply("users_propose_create", "{\"username\":\"fixture\",\"displayName\":\"Fixture\"}");
        var result = service.chat(request("deepseek-flash"), "actor");
        assertNull(result.proposal());
        assertEquals("error", result.steps().getLast().status());
        assertEquals("user-management", result.steps().getLast().moduleCode());
        assertEquals("create", result.steps().getLast().operationCode());
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
    void malformedArgumentsNeverExecute() {
        reply("users_search", "[]");
        var failedStep = service.chat(request("deepseek-flash"), "actor").steps().getLast();
        assertEquals("error", failedStep.status());
        assertEquals("user-management", failedStep.moduleCode());
        assertEquals("query", failedStep.operationCode());
        verifyNoInteractions(users, actions);
    }

    private void batch(ModelConnectionService.ToolCall... calls) {
        when(connections.chat(eq(provider), anyString(), anyList(), anyList()))
                .thenReturn(new ModelConnectionService.ChatReply("", List.of(calls)));
    }

    private ModelConnectionService.ToolCall search(String id, String arguments) {
        return new ModelConnectionService.ToolCall(id, "users_search", arguments);
    }

    @Test
    void repeatedListUsersCallsPerformOnlyOneAuthorizedLookup() {
        batch(search("first", "{}"), search("second", "{\"search\":\"  \"}"));
        var user =
                new AiUserDirectoryPort.UserView(
                        UUID.randomUUID(), "visible", "Visible", true, 1, null, null);
        when(users.list(new PageRequest(1, 10, ""), "actor"))
                .thenReturn(new PageResult<>(List.of(user), 1, 1, 10));

        var result = service.chat(request("deepseek-flash"), "actor");

        assertEquals(List.of(user), result.users());
        assertEquals(
                new AiAssistantService.ExecutionStep(
                        "USER_SEARCH", "success", "user-management", "query"),
                result.steps().getLast());
        verify(users, times(1)).list(new PageRequest(1, 10, ""), "actor");
        verifyNoMoreInteractions(users);
        verifyNoInteractions(actions);
    }

    @Test
    void distinctReadOnlyQueriesMergeOverlappingRecordsAndTrimDuplicateConditions() {
        batch(
                search("first", "{\"search\":\"alpha\"}"),
                search("second", "{\"search\":\"beta\"}"),
                search("third", "{\"search\":\" alpha \"}"));
        var alpha =
                new AiUserDirectoryPort.UserView(
                        UUID.randomUUID(), "alpha", "Alpha", true, 1, null, null);
        var overlap =
                new AiUserDirectoryPort.UserView(
                        UUID.randomUUID(), "common", "Common", false, 2, null, null);
        var beta =
                new AiUserDirectoryPort.UserView(
                        UUID.randomUUID(), "beta", "Beta", true, 3, null, null);
        when(users.list(new PageRequest(1, 10, "alpha"), "actor"))
                .thenReturn(new PageResult<>(List.of(alpha, overlap), 2, 1, 10));
        when(users.list(new PageRequest(1, 10, "beta"), "actor"))
                .thenReturn(new PageResult<>(List.of(overlap, beta), 2, 1, 10));

        var result = service.chat(request("deepseek-flash"), "actor");

        assertEquals(List.of(alpha, overlap, beta), result.users());
        assertEquals("USER_SEARCH", result.steps().getLast().code());
        assertTrue(result.message().contains("查询“alpha”：找到 2 条"));
        assertTrue(result.message().contains("查询“beta”：找到 2 条"));
        verify(users, times(1)).list(new PageRequest(1, 10, "alpha"), "actor");
        verify(users, times(1)).list(new PageRequest(1, 10, "beta"), "actor");
        verifyNoMoreInteractions(users);
        verifyNoInteractions(actions);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[]",
                "not-json",
                "{\"search\":true}",
                "{\"search\":null}",
                "{\"search\":{},\"unexpected\":true}",
                "{\"unexpected\":\"all\"}"
            })
    void invalidSearchBatchIsRejectedBeforeAnyDirectoryLookup(String invalid) {
        batch(search("first", "{\"search\":\"valid\"}"), search("second", invalid));

        var result = service.chat(request("deepseek-flash"), "actor");

        assertNull(result.users());
        assertEquals("error", result.steps().getLast().status());
        verifyNoInteractions(users, actions);
    }

    @Test
    void mixedReadAndWriteBatchNeverQueriesOrCreatesAPendingAction() {
        when(guard.hasGlobal("actor", "platform:users:write")).thenReturn(true);
        batch(
                search("first", "{}"),
                new ModelConnectionService.ToolCall(
                        "second",
                        "users_propose_create",
                        "{\"username\":\"fixture\",\"displayName\":\"Fixture\"}"));

        var result = service.chat(request("deepseek-flash"), "actor");

        assertNull(result.proposal());
        assertNull(result.users());
        assertEquals("error", result.steps().getLast().status());
        verifyNoInteractions(users, actions);
    }

    @Test
    void multipleWriteProposalsStillRequireSeparateRequests() {
        when(guard.hasGlobal("actor", "platform:users:write")).thenReturn(true);
        batch(
                new ModelConnectionService.ToolCall(
                        "first",
                        "users_propose_create",
                        "{\"username\":\"fixture\",\"displayName\":\"Fixture\"}"),
                new ModelConnectionService.ToolCall(
                        "second",
                        "users_propose_create",
                        "{\"username\":\"fixture2\",\"displayName\":\"Fixture 2\"}"));

        var result = service.chat(request("deepseek-flash"), "actor");

        assertNull(result.proposal());
        assertEquals("error", result.steps().getLast().status());
        verifyNoInteractions(users, actions);
    }

    @Test
    void unknownToolInBatchIsRejectedWithoutPartialExecution() {
        batch(
                search("first", "{}"),
                new ModelConnectionService.ToolCall("second", "unknown", "{}"));
        var result = service.chat(request("deepseek-flash"), "actor");
        assertEquals("error", result.steps().getLast().status());
        verifyNoInteractions(users, actions);
    }

    @Test
    void excessiveBatchIsRejectedBeforeAnyLookup() {
        batch(
                search("one", "{}"),
                search("two", "{}"),
                search("three", "{}"),
                search("four", "{}"),
                search("five", "{}"));
        var result = service.chat(request("deepseek-flash"), "actor");
        assertEquals("error", result.steps().getLast().status());
        verifyNoInteractions(users, actions);
    }

    @Test
    void selectedModelWithoutToolContractCannotExecuteABatch() {
        batch(search("first", "{}"), search("second", "{}"));
        var result = service.chat(request("unknown-model"), "actor");
        assertEquals("error", result.steps().getLast().status());
        verifyNoInteractions(users, actions);
    }

    @Test
    void combinedTableKeepsAllMatchesWhileTextFitsSubsequentChatHistory() {
        batch(
                search("first", "{\"search\":\"first\"}"),
                search("second", "{\"search\":\"second\"}"));
        var matches =
                java.util.stream.IntStream.range(0, 20)
                        .mapToObj(
                                index ->
                                        new AiUserDirectoryPort.UserView(
                                                UUID.randomUUID(),
                                                "u".repeat(97) + String.format("%03d", index),
                                                "d".repeat(200),
                                                true,
                                                1,
                                                null,
                                                null))
                        .toList();
        when(users.list(new PageRequest(1, 10, "first"), "actor"))
                .thenReturn(new PageResult<>(matches.subList(0, 10), 20, 1, 10));
        when(users.list(new PageRequest(1, 10, "second"), "actor"))
                .thenReturn(new PageResult<>(matches.subList(10, 20), 20, 1, 10));

        var result = service.chat(request("deepseek-flash"), "actor");

        assertEquals(matches, result.users());
        assertTrue(result.message().length() <= 5000);
        assertTrue(result.message().contains(matches.getFirst().id().toString()));
        assertFalse(result.message().contains(matches.getLast().id().toString()));
        verifyNoInteractions(actions);
    }

    @Test
    void accountTextCannotBreakMarkdownRowsOrIntroduceFormatting() {
        reply("users_search", "{}");
        var user =
                new AiUserDirectoryPort.UserView(
                        UUID.randomUUID(),
                        "qa_fixture",
                        "[链接](https://example.com)|**姓名**\n第二行",
                        true,
                        1,
                        null,
                        null);
        when(users.list(new PageRequest(1, 10, ""), "actor"))
                .thenReturn(new PageResult<>(List.of(user), 1, 1, 10));

        var result = service.chat(request("deepseek-flash"), "actor");

        assertEquals(List.of(user), result.users());
        assertTrue(result.message().contains("qa\\_fixture"));
        assertTrue(
                result.message()
                        .contains("\\[链接\\]\\(https://example\\.com\\)\\|\\*\\*姓名\\*\\* 第二行"));
        assertFalse(result.message().contains("\n第二行"));
        verifyNoInteractions(actions);
    }

    @Test
    void escapedMarkdownStillFitsTheChatHistoryLimit() {
        reply("users_search", "{}");
        var matches =
                java.util.stream.IntStream.range(0, 10)
                        .mapToObj(
                                index ->
                                        new AiUserDirectoryPort.UserView(
                                                UUID.randomUUID(),
                                                "u" + "_".repeat(99),
                                                "&".repeat(200),
                                                true,
                                                1,
                                                null,
                                                null))
                        .toList();
        when(users.list(new PageRequest(1, 10, ""), "actor"))
                .thenReturn(new PageResult<>(matches, 10, 1, 10));

        var result = service.chat(request("deepseek-flash"), "actor");

        assertEquals(matches, result.users());
        assertTrue(result.message().length() <= 5000);
        assertTrue(result.message().contains(matches.getFirst().id().toString()));
        assertFalse(result.message().contains(matches.getLast().id().toString()));
        assertTrue(result.message().contains("其余记录"));
        verifyNoInteractions(actions);
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
                                new PendingAiActionStore.Target(
                                        userId, "fixture", "Fixture", true, 7),
                                Instant.now().plusSeconds(180)));
        when(users.find(userId, "actor"))
                .thenReturn(
                        new AiUserDirectoryPort.UserView(
                                userId, "fixture", "Fixture", true, 7, null, null));
        service.confirm(id, new AiAssistantService.ConfirmAction(null), "actor");
        verify(guard).require("actor", "platform:users:write");
        verify(guard).requireGlobal("actor", "platform:users:write");
        verify(users)
                .update(userId, new AiUserDirectoryPort.UpdateUser("Updated", false, 7L), "actor");
    }

    @Test
    void confirmationRejectsAChangedTargetBeforeApplyingTheProposedMutation() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var snapshot = new PendingAiActionStore.Target(userId, "fixture", "Fixture", true, 7);
        when(actions.take(id, "actor"))
                .thenReturn(
                        new PendingAiActionStore.PendingAction(
                                id,
                                "actor",
                                PendingAiActionStore.Kind.UPDATE_USER,
                                userId,
                                7,
                                null,
                                "Changed by proposal",
                                false,
                                snapshot,
                                Instant.now().plusSeconds(180)));
        when(users.find(userId, "actor"))
                .thenReturn(
                        new AiUserDirectoryPort.UserView(
                                userId, "fixture", "Renamed", true, 8, null, null));

        var failure =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.confirm(
                                        id, new AiAssistantService.ConfirmAction(null), "actor"));

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertEquals("staleRecord", failure.getReason());
        verify(users, never()).update(any(), any(), anyString());
    }

    @Test
    void confirmationRejectsLegacyMutationWithoutATargetSnapshot() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(actions.take(id, "actor"))
                .thenReturn(
                        new PendingAiActionStore.PendingAction(
                                id,
                                "actor",
                                PendingAiActionStore.Kind.DELETE_USER,
                                userId,
                                7,
                                null,
                                null,
                                null,
                                null,
                                Instant.now().plusSeconds(180)));

        var failure =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.confirm(
                                        id, new AiAssistantService.ConfirmAction(null), "actor"));

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertEquals("staleRecord", failure.getReason());
        verifyNoInteractions(users);
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
