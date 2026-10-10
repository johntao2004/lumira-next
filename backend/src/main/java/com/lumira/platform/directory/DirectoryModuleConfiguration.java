package com.lumira.platform.directory;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runtime descriptor for users, roles, organizations, and memberships. */
@Configuration(proxyBeanMethods = false)
public class DirectoryModuleConfiguration {
    @Bean
    PlatformModule directoryModule() {
        return new PlatformModule(
                "directory", "组织与目录", "1.0.0", List.of("identity", "platform", "files"), true);
    }
}
