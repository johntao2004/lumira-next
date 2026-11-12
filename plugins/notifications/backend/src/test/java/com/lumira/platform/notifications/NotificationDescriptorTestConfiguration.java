package com.lumira.platform.notifications;

import static org.mockito.Mockito.mock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/** Supplies inert consumer collaborators when testing module descriptor registration alone. */
@TestConfiguration(proxyBeanMethods = false)
public class NotificationDescriptorTestConfiguration {
    @Bean
    NotificationStore notificationDescriptorStore() {
        return mock(NotificationStore.class);
    }

    @Bean
    FeedbackNotificationAudience notificationDescriptorAudience() {
        return mock(FeedbackNotificationAudience.class);
    }

    @Bean
    ObjectMapper notificationDescriptorObjectMapper() {
        return new ObjectMapper();
    }
}
