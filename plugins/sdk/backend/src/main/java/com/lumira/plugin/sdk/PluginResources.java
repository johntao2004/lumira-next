package com.lumira.plugin.sdk;

import java.time.Duration;

/** Owned background work and subscriptions; the host drains calls and closes registrations. */
public interface PluginResources {
    AutoCloseable schedule(String name, Duration interval, Runnable task);

    <T extends AutoCloseable> T own(String name, T resource);
}
