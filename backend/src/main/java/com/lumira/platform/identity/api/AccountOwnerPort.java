package com.lumira.platform.identity.api;

import java.util.UUID;

/**
 * Minimal account lookup contract exposed to modules that need an owner id.
 * The identity repository and its locking semantics remain private.
 */
public interface AccountOwnerPort {
    UUID id(String username, boolean lock);
}
