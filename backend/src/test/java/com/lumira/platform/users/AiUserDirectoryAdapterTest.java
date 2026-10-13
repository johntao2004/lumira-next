package com.lumira.platform.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.AiUserDirectoryPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiUserDirectoryAdapterTest {
    private static final String ACTOR = "admin";
    private static final UUID USER_ID = UUID.fromString("f742c55e-a18e-4595-bfb9-ce5e69fbf8ca");

    private final UserService users = mock(UserService.class);
    private final AiUserDirectoryAdapter adapter = new AiUserDirectoryAdapter(users);

    @Test
    void searchDelegatesToTheAuthorizedUserServiceAndKeepsItsSafeProjection() {
        var request = new PageRequest(1, 10, "qa");
        var user = new UserSummary(USER_ID, "qa-user", "QA User", true, 4, 4096L, 2L);
        when(users.listForAi(request, ACTOR))
                .thenReturn(new PageResult<>(List.of(user), 12, 1, 10));

        var result = adapter.list(request, ACTOR);

        assertEquals(
                new PageResult<>(
                        List.of(
                                new AiUserDirectoryPort.UserView(
                                        USER_ID, "qa-user", "QA User", true, 4, 4096L, 2L)),
                        12,
                        1,
                        10),
                result);
        verify(users).listForAi(request, ACTOR);
    }

    @Test
    void targetSnapshotReadUsesTheAuthorizedUserServiceAndPreservesItsProjection() {
        var user = new UserSummary(USER_ID, "qa-user", "QA User", true, 4, 4096L, 2L);
        when(users.findForAi(USER_ID, ACTOR)).thenReturn(user);

        assertEquals(
                new AiUserDirectoryPort.UserView(USER_ID, "qa-user", "QA User", true, 4, 4096L, 2L),
                adapter.find(USER_ID, ACTOR));
        verify(users).findForAi(USER_ID, ACTOR);
    }

    @Test
    void proposedWritesDelegateToTheExistingUserService() {
        var user = new UserSummary(USER_ID, "qa-user", "QA User", true, 4, null, null);
        when(users.createForAi(
                        new UserService.CreateUser("qa-user", "QA User", "synthetic-password"),
                        ACTOR))
                .thenReturn(user);
        when(users.updateForAi(USER_ID, new UserService.UpdateUser("QA User 2", true, 4L), ACTOR))
                .thenReturn(new UserSummary(USER_ID, "qa-user", "QA User 2", true, 5, null, null));

        assertEquals(
                new AiUserDirectoryPort.UserView(
                        USER_ID, "qa-user", "QA User", true, 4, null, null),
                adapter.create(
                        new AiUserDirectoryPort.CreateUser(
                                "qa-user", "QA User", "synthetic-password"),
                        ACTOR));
        assertEquals(
                new AiUserDirectoryPort.UserView(
                        USER_ID, "qa-user", "QA User 2", true, 5, null, null),
                adapter.update(
                        USER_ID, new AiUserDirectoryPort.UpdateUser("QA User 2", true, 4L), ACTOR));
        adapter.delete(USER_ID, 5, ACTOR);

        verify(users)
                .createForAi(
                        new UserService.CreateUser("qa-user", "QA User", "synthetic-password"),
                        ACTOR);
        verify(users)
                .updateForAi(USER_ID, new UserService.UpdateUser("QA User 2", true, 4L), ACTOR);
        verify(users).deleteForAi(USER_ID, 5, ACTOR);
    }
}
