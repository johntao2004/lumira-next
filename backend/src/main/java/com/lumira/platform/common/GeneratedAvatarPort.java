package com.lumira.platform.common;

import java.util.UUID;

/** Generates a stable PNG avatar for an account without changing account storage. */
public interface GeneratedAvatarPort {
    byte[] generate(UUID accountId);
}
