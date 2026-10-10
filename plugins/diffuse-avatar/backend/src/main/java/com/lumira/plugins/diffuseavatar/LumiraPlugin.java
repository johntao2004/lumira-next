package com.lumira.plugins.diffuseavatar;

import com.lumira.platform.common.GeneratedAvatarPort;
import com.lumira.plugin.sdk.PluginEntry;
import org.pf4j.PluginWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public final class LumiraPlugin extends PluginEntry {
    public LumiraPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public Class<?> configuration() {
        return DiffuseAvatarConfiguration.class;
    }

    @Configuration(proxyBeanMethods = false)
    public static class DiffuseAvatarConfiguration {
        @Bean
        GeneratedAvatarPort generatedAvatarPort() {
            return new DiffuseAvatarGenerator();
        }
    }
}
