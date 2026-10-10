package com.lumira.platform.common;

import java.util.UUID;

/** User operations that the platform AI module may request on behalf of an authorized actor. */
public interface AiUserDirectoryPort {
    /** Safe account projection returned to the AI client; it contains no credential material. */
    record UserView(
            UUID id,
            String username,
            String displayName,
            boolean enabled,
            long version,
            Long fileQuotaBytesOverride,
            Long fileQuotaFilesOverride) {}

    record CreateUser(String username, String displayName, String password) {}

    record UpdateUser(String displayName, Boolean enabled, Long version) {}

    PageResult<UserView> list(PageRequest request, String actor);

    /** Read one currently visible user through the directory's normal authorization path. */
    UserView find(UUID id, String actor);

    UserView create(CreateUser input, String actor);

    UserView update(UUID id, UpdateUser input, String actor);

    void delete(UUID id, long version, String actor);
}
