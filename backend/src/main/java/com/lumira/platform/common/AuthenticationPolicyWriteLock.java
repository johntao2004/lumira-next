package com.lumira.platform.common;

/** Acquires the host-owned authentication-policy row lock before auth-sensitive mutations. */
public interface AuthenticationPolicyWriteLock {
    void lockForAuthentication();

    void lockForUpdate();
}
