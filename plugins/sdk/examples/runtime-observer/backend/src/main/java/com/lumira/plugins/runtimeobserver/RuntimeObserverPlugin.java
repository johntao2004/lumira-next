package com.lumira.plugins.runtimeobserver;

import com.lumira.plugin.sdk.PluginEntry;
import org.pf4j.PluginWrapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

public final class RuntimeObserverPlugin extends PluginEntry {
    public RuntimeObserverPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public Class<?> configuration() {
        return PluginConfiguration.class;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RuntimeObserverModuleConfiguration.class)
    public static class PluginConfiguration {}
}
