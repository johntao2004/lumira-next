package com.lumira.platform.localization;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Descriptor for the optional runtime-localization module. */
@Configuration(proxyBeanMethods = false)
public class LocalizationModuleConfiguration {
    @Bean
    PlatformModule localizationModule() {
        return new PlatformModule("localization", "运行时本地化", "1.0.0", List.of("platform"));
    }
}
