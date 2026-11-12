package com.lumira.platform.monitoring;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runtime descriptor owned by the read-only operations dashboard. */
@Configuration(proxyBeanMethods = false)
public class MonitoringModuleConfiguration {
    @Bean
    PlatformModule monitoringModule() {
        return new PlatformModule(
                "monitoring",
                "运行监控",
                "1.0.0",
                List.of("platform", "communication", "events", "notifications", "identity"),
                false);
    }
}
