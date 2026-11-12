package com.lumira.plugins.clusterruntime;

import com.lumira.plugin.sdk.PluginEntry;
import org.pf4j.PluginWrapper;

public final class ClusterRuntimePlugin extends PluginEntry {
    public ClusterRuntimePlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public Class<?> configuration() {
        return Configuration.class;
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    public static class Configuration {}
}
