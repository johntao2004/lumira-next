package com.lumira.platform.navigation;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runtime descriptor owned by the server-owned navigation module. */
@Configuration(proxyBeanMethods = false)
public class NavigationModuleConfiguration {
    @Bean
    PlatformModule navigationModule() {
        return new PlatformModule("navigation", "导航管理", "1.0.0", List.of("platform"), true);
    }
}
