package com.lumira.platform.common;

/**
 * 在短业务写入边界重新检查会话授权版本。 Rechecks the session authorization revision at a short business write
 * boundary.
 */
public interface AuthorizationContextBoundaryPort {
    /**
     * 锁定并复核当前请求授权版本；失效授权不得继续写入。 Locks and revalidates current request authorization; stale
     * authorization must not write.
     */
    void lockAndValidateCurrentRequest();
}
