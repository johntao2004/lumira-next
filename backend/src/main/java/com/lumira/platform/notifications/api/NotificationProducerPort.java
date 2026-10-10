package com.lumira.platform.notifications.api;

import java.util.UUID;

/**
 * Producer-facing notification contract. Feature modules publish events through this port instead
 * of depending on the notification application service.
 */
public interface NotificationProducerPort {
    void publishFeedback(
            UUID recipientId,
            UUID feedbackId,
            long version,
            String title,
            String status,
            boolean handled);
}
