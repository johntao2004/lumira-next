package com.lumira.platform.notifications.api;

import java.util.UUID;

/**
 * Producer-facing notification contract. Feature modules publish events through this port instead
 * of depending on the notification application service.
 */
public interface NotificationProducerPort extends com.lumira.platform.common.PluginServicePort {
    @Override
    default String ownerPluginId() {
        return "notifications";
    }

    @Override
    default String serviceType() {
        return "notification-producer";
    }

    void publishFeedback(
            UUID recipientId,
            UUID feedbackId,
            long version,
            String title,
            String status,
            boolean handled);

    /**
     * Versioned overload that carries the originating audit/business operation through to the event
     * outbox. The default preserves binary compatibility with already installed plugins.
     */
    default void publishFeedback(
            UUID operationId,
            UUID auditOperationId,
            UUID recipientId,
            UUID feedbackId,
            long version,
            String title,
            String status,
            boolean handled) {
        publishFeedback(recipientId, feedbackId, version, title, status, handled);
    }
}
