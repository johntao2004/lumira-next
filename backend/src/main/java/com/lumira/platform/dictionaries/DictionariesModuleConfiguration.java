package com.lumira.platform.dictionaries;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runtime descriptor owned by the dictionary module. */
@Configuration(proxyBeanMethods = false)
public class DictionariesModuleConfiguration {
    @Bean
    PlatformModule dictionariesModule() {
        return new PlatformModule(
                "dictionaries", "字典管理", "1.0.0", List.of("identity", "platform"), true);
    }
}
