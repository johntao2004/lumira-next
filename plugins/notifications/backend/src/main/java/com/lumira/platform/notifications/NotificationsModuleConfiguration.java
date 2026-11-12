package com.lumira.platform.notifications;

import com.lumira.platform.common.DurableEventConsumerPort;
import com.lumira.platform.common.DurableEventProducerPort;
import com.lumira.platform.modules.PlatformModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Runtime descriptor owned by the in-app notification module. */
@Configuration(proxyBeanMethods = false)
public class NotificationsModuleConfiguration {
    @Bean
    PlatformModule notificationsModule() {
        return new PlatformModule(
                "notifications", "站内通知", "1.0.0", List.of("platform", "events"), false);
    }

    @Bean
    DurableEventProducerPort notificationEventCatalog() {
        return () ->
                List.of(
                        new DurableEventProducerPort.EventType(
                                NotificationAdminService.BROADCAST_SOURCE,
                                NotificationAdminService.BROADCAST_EVENT),
                        new DurableEventProducerPort.EventType(
                                NotificationService.FEEDBACK_SOURCE,
                                NotificationService.FEEDBACK_EVENT));
    }

    @Bean
    DurableEventConsumerPort notificationBroadcastConsumer(
            NotificationStore store, ObjectMapper mapper) {
        return new NotificationBroadcastEventConsumer(store, mapper);
    }

    @Bean
    DurableEventConsumerPort feedbackNotificationConsumer(
            NotificationStore store,
            ObjectMapper mapper,
            FeedbackNotificationAudience feedbackAudience) {
        return new FeedbackNotificationEventConsumer(store, mapper, feedbackAudience);
    }
}
