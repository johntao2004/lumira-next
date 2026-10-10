package com.lumira.platform.sensitive;

import com.lumira.plugin.sdk.PluginEntry;
import org.pf4j.PluginWrapper;
import org.springframework.context.annotation.*;

public final class LumiraPlugin extends PluginEntry {
    public LumiraPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public Class<?> configuration() {
        return Configuration.class;
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ComponentScan("com.lumira.platform.sensitive")
    @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass = true)
    public static class Configuration {}
}
