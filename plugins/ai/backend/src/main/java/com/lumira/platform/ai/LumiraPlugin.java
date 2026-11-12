package com.lumira.platform.ai;

import com.lumira.plugin.sdk.PluginEntry;
import org.pf4j.PluginWrapper;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.transaction.annotation.EnableTransactionManagement;

public final class LumiraPlugin extends PluginEntry {
    public LumiraPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public Class<?> configuration() {
        return Configuration.class;
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ComponentScan("com.lumira.platform.ai")
    @EnableTransactionManagement(proxyTargetClass = true)
    public static class Configuration {
        @org.springframework.context.annotation.Bean
        Object pendingActionCleanup(
                PendingAiActionStore actions, com.lumira.plugin.sdk.PluginResources resources) {
            resources.schedule(
                    "pending-actions-cleanup",
                    java.time.Duration.ofMinutes(1),
                    actions::cleanupExpired);
            return new Object();
        }
    }
}
