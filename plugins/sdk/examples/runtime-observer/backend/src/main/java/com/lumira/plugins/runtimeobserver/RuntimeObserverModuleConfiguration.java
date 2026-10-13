package com.lumira.plugins.runtimeobserver;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RuntimeObserverModuleConfiguration {
    @Bean
    PlatformModule runtimeObserverModule() {
        return new PlatformModule("runtime-observer", "运行展示屏", "1.0.0", List.of("platform"));
    }
}
