package com.lumira.platform.files.api;

import com.lumira.platform.files.AvatarService;
import java.util.UUID;

/** Explicit HTTP response; persistence records remain internal to the feature. */
public record AvatarResponse(UUID fileId, long version, boolean generated) {
    public static AvatarResponse from(AvatarService.AvatarView value) {
        if (value == null) return null;
        return new AvatarResponse(value.fileId(), value.version(), value.generated());
    }
}
