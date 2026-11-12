package com.lumira.platform.plugins;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Lexical ownership of work, independent of the drain lease held through transaction completion.
 */
final class PluginInvocationContext implements AutoCloseable {
    record Owner(String moduleId, String digest, long generation, UUID nodeId) {}

    private static final ThreadLocal<PluginInvocationContext> CURRENT = new ThreadLocal<>();
    private final PluginInvocationContext previous;
    private final Owner owner;
    private final Thread thread = Thread.currentThread();
    private boolean closed;

    private PluginInvocationContext(Owner owner) {
        this.owner = owner;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    static PluginInvocationContext open(Owner owner) {
        return new PluginInvocationContext(java.util.Objects.requireNonNull(owner));
    }

    static List<Owner> owners() {
        var current = CURRENT.get();
        if (current == null) return List.of();
        var owners = new LinkedHashSet<Owner>();
        for (var scope = current; scope != null; scope = scope.previous) owners.add(scope.owner);
        return List.copyOf(owners);
    }

    @Override
    public void close() {
        if (closed) return;
        if (thread != Thread.currentThread() || CURRENT.get() != this)
            throw new IllegalStateException("pluginInvocationScopeOrder");
        closed = true;
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
