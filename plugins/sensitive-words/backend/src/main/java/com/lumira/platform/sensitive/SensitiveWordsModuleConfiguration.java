package com.lumira.platform.sensitive;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Descriptor for the optional sensitive-word policy module. */
@Configuration(proxyBeanMethods = false)
public class SensitiveWordsModuleConfiguration {
    @Bean
    PlatformModule sensitiveWordsModule() {
        return new PlatformModule(
                "sensitive-words", "敏感词策略", "1.0.8", List.of("platform", "dictionaries"));
    }
}
