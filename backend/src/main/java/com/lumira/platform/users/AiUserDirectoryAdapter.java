package com.lumira.platform.users;

import com.lumira.platform.common.AiUserDirectoryPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Adapts assistant requests to the directory's authorization, validation, audit, and persistence path. */
@Service
public class AiUserDirectoryAdapter implements AiUserDirectoryPort {
    private final UserService users;

    public AiUserDirectoryAdapter(UserService users) {
        this.users = users;
    }

    @Override
    public PageResult<UserView> list(PageRequest request, String actor) {
        PageResult<UserSummary> result = users.list(request, actor);
        return new PageResult<>(result.items().stream().map(AiUserDirectoryAdapter::view).toList(),
            result.total(), result.page(), result.pageSize());
    }

    @Override
    public UserView create(CreateUser input, String actor) {
        return view(users.create(new UserService.CreateUser(input.username(), input.displayName(), input.password()), actor));
    }

    @Override
    public UserView update(UUID id, UpdateUser input, String actor) {
        return view(users.update(id, new UserService.UpdateUser(input.displayName(), input.enabled(), input.version()), actor));
    }

    @Override
    public void delete(UUID id, long version, String actor) {
        users.delete(id, version, actor);
    }

    private static UserView view(UserSummary user) {
        return new UserView(user.id(), user.username(), user.displayName(), user.enabled(), user.version(),
            user.fileQuotaBytesOverride(), user.fileQuotaFilesOverride());
    }
}
