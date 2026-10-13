package com.lumira.plugins.verificationdebugger;

import com.lumira.plugin.sdk.PluginEntry;
import org.pf4j.PluginWrapper;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

public final class VerificationDebuggerPlugin extends PluginEntry {
    public VerificationDebuggerPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public Class<?> configuration() {
        return PluginConfiguration.class;
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.lumira.plugins.verificationdebugger")
    public static class PluginConfiguration {}
}
