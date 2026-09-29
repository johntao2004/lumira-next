package com.lumira.platform.audit;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Descriptor for the optional audit query module. */
@Configuration(proxyBeanMethods = false)
public class AuditModuleConfiguration {
    @Bean
    PlatformModule auditConsoleModule() {
        return new PlatformModule("audit-console", "审计查询", "1.0.0", List.of("platform"));
    }
}
