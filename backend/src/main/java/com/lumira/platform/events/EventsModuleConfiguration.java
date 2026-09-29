package com.lumira.platform.events;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runtime descriptor owned by the durable event module. */
@Configuration(proxyBeanMethods = false)
public class EventsModuleConfiguration {
    @Bean
    PlatformModule eventsModule() {
        return new PlatformModule("events", "事件总线", "1.0.0", List.of("platform"), true);
    }
}
