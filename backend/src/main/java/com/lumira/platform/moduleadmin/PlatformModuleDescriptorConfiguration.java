package com.lumira.platform.moduleadmin;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Descriptor for the required platform-management module. */
@Configuration(proxyBeanMethods = false)
public class PlatformModuleDescriptorConfiguration {
    @Bean
    PlatformModule platformModule() {
        return new PlatformModule("platform", "平台管理", "1.0.0", List.of("identity"), true);
    }
}
