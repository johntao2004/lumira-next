package com.lumira.plugins.alipaypay;

import com.lumira.plugin.sdk.PluginEntry;
import org.pf4j.PluginWrapper;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

public final class LumiraPlugin extends PluginEntry {
    public LumiraPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public Class<?> configuration() {
        return AlipayConfiguration.class;
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.lumira.plugins.alipaypay")
    public static class AlipayConfiguration {}
}
