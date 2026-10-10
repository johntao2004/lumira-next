package com.lumira.platform.common;

import java.util.UUID;

/** Content gate evaluated against the current default registration role without an account. */
public interface RegistrationContentPolicyPort {
    boolean blocked(UUID roleId, String text, String source);
}
