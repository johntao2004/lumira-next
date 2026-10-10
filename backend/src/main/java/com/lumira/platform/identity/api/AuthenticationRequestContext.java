package com.lumira.platform.identity.api;

import java.util.UUID;

/** Thread-scoped snapshot already validated by the host authorization filter. */
public final class AuthenticationRequestContext {
    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    public record Snapshot(
            String principalName,
            UUID userId,
            EffectiveAuthenticationPolicyPort.EffectivePolicy policy,
            RequestAuthorizationContext authorization) {}

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private AuthenticationRequestContext() {}

    public static Scope bind(Snapshot snapshot) {
        Snapshot previous = CURRENT.get();
        CURRENT.set(snapshot);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public static Snapshot current() {
        return CURRENT.get();
    }
}
