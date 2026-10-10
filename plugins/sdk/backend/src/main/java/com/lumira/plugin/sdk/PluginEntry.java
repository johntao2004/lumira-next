package com.lumira.plugin.sdk;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/** Lumira SDK v1. The host owns authentication, transactions and resource registration. */
public abstract class PluginEntry extends Plugin {
    protected PluginEntry(PluginWrapper wrapper) {
        super(wrapper);
    }

    public abstract Class<?> configuration();

    /** Must throw when the plugin is not ready. Called before publication. */
    public void checkHealth() {}
}
