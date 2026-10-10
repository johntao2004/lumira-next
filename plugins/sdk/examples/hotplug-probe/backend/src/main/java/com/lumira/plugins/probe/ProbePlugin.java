package com.lumira.plugins.probe;

import com.lumira.platform.modules.ModuleEndpoint;
import com.lumira.plugin.sdk.PluginEntry;
import org.pf4j.PluginWrapper;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;

public class ProbePlugin extends PluginEntry {
    public ProbePlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public Class<?> configuration() {
        return Config.class;
    }

    @Configuration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        ProbeController probe() {
            return new ProbeController();
        }
    }

    @ModuleEndpoint("hotplug-probe")
    @RestController
    public static class ProbeController {
        @GetMapping("/api/plugins/hotplug-probe/status")
        public java.util.Map<String, String> status() {
            return java.util.Map.of(
                    "code",
                    "new-runtime-code",
                    "loader",
                    getClass().getClassLoader().getClass().getName());
        }
    }
}
