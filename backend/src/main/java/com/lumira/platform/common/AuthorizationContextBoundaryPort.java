package com.lumira.platform.common;

/** Rechecks the session authorization revision at a short business write boundary. */
public interface AuthorizationContextBoundaryPort {
    void lockAndValidateCurrentRequest();
}
