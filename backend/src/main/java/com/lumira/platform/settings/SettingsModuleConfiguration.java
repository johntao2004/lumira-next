package com.lumira.platform.settings;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runtime descriptor owned by the settings module. */
@Configuration(proxyBeanMethods = false)
public class SettingsModuleConfiguration {
    @Bean
    PlatformModule settingsModule() {
        return new PlatformModule(
                "settings", "系统设置", "1.0.0", List.of("identity", "platform", "dictionaries"), true);
    }
}
