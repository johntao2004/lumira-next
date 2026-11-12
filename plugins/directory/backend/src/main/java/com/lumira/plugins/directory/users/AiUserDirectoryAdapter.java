package com.lumira.plugins.directory.users;

import com.lumira.platform.common.DirectoryAiServicePort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.PlatformOperation;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Adapts assistant requests to the directory's authorization, validation, audit, and persistence
 * path.
 */
@Service
@org.springframework.context.annotation.Primary
public class AiUserDirectoryAdapter implements DirectoryAiServicePort {
    private final UserService users;

    public AiUserDirectoryAdapter(UserService users) {
        this.users = users;
    }

    @Override
    public PageResult<UserView> list(PageRequest request, String actor) {
        PageResult<UserSummary> result = users.listForAi(request, actor);
        return result.map(AiUserDirectoryAdapter::view);
    }

    @Override
    public UserView find(UUID id, String actor) {
        return view(users.findForAi(id, actor));
    }

    @Override
    public UserView findForOperation(UUID id, String actor, PlatformOperation operation) {
        return view(users.findForAiAction(id, actor, operation));
    }

    @Override
    public UserView create(CreateUser input, String actor) {
        return view(
                users.createForAi(
                        new UserService.CreateUser(
                                input.username(), input.displayName(), input.password()),
                        actor));
    }

    @Override
    public UserView update(UUID id, UpdateUser input, String actor) {
        return view(
                users.updateForAi(
                        id,
                        new UserService.UpdateUser(
                                input.displayName(), input.enabled(), input.version()),
                        actor));
    }

    @Override
    public void delete(UUID id, long version, String actor) {
        users.deleteForAi(id, version, actor);
    }

    private static UserView view(UserSummary user) {
        return new UserView(
                user.id(),
                user.username(),
                user.displayName(),
                user.enabled(),
                user.version(),
                user.fileQuotaBytesOverride(),
                user.fileQuotaFilesOverride());
    }
}
