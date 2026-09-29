package com.lumira.platform.identity.api;

import java.util.UUID;

/** Read-only identity-facing view of avatar ownership. */
public interface AvatarStatusPort {
    boolean hasAvatar(UUID owner);
}
