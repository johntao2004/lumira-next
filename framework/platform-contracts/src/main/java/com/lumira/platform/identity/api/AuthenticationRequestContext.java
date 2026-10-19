package com.lumira.platform.identity.api;

import java.util.UUID;

/** 宿主授权过滤器已验证的线程范围快照。 Thread-scoped snapshot already validated by the host authorization filter. */
public final class AuthenticationRequestContext {
    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    public record Snapshot(
            String principalName,
            UUID userId,
            EffectiveAuthenticationPolicyPort.EffectivePolicy policy,
            RequestAuthorizationContext authorization) {}

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        /**
         * 恢复先前线程快照或清除当前值，避免请求间串用。 Restores the previous thread snapshot or clears it to prevent
         * cross-request reuse.
         */
        @Override
        void close();
    }

    private AuthenticationRequestContext() {}

    /**
     * 只绑定宿主已验证的快照，不执行认证或授权；调用方必须用 try-with-resources 关闭并恢复原上下文。 Binds only a host-validated
     * snapshot without authenticating or authorizing; callers must close the scope with
     * try-with-resources to restore prior context.
     */
    public static Scope bind(Snapshot snapshot) {
        Snapshot previous = CURRENT.get();
        CURRENT.set(snapshot);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    /** 返回当前线程的已绑定快照；未绑定时为 null。 Returns the current thread snapshot, or null when unbound. */
    public static Snapshot current() {
        return CURRENT.get();
    }
}
