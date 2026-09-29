package com.lumira.platform.identity;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runtime descriptor owned by the identity module. */
@Configuration(proxyBeanMethods = false)
public class IdentityModuleConfiguration {
    @Bean
    PlatformModule identityModule() {
        return new PlatformModule("identity", "身份与权限", "1.0.0", List.of(), true);
    }
}
