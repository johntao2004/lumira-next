package com.lumira.platform.notifications;

import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runtime descriptor owned by the in-app notification module. */
@Configuration(proxyBeanMethods = false)
public class NotificationsModuleConfiguration {
    @Bean
    PlatformModule notificationsModule() {
        return new PlatformModule("notifications", "站内通知", "1.0.0", List.of("platform"), true);
    }
}
