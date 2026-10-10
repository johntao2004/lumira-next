package com.lumira.platform.communication;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Descriptor for the required mail/SMS communication module. */
@Configuration(proxyBeanMethods = false)
public class CommunicationModuleConfiguration {
    @Bean
    PlatformModule communicationModule() {
        return new PlatformModule(
                "communication",
                "消息投递",
                "1.0.0",
                List.of("identity", "platform", "settings"),
                true);
    }
}
