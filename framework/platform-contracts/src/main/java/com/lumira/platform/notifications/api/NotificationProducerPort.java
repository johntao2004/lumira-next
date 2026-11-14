package com.lumira.platform.notifications.api;

import java.util.UUID;

/**
 * 业务模块经此契约发布通知事件，不依赖通知应用服务。 Producer-facing notification contract. Feature modules publish events
 * through this port instead of depending on the notification application service.
 */
public interface NotificationProducerPort extends com.lumira.platform.common.PluginServicePort {
    /** 返回拥有通知生产契约的插件标识。 Returns the identifier of the plugin owning notification production. */
    @Override
    default String ownerPluginId() {
        return "notifications";
    }

    /** 返回通知生产服务的契约类型标识。 Returns the contract type identifier for notification production. */
    @Override
    default String serviceType() {
        return "notification-producer";
    }

    /**
     * 发布指定反馈版本的通知事件；须参与调用方业务事务，不等同已投递。 Publishes a notification for the feedback version within the
     * caller transaction; does not imply delivery.
     */
    void publishFeedback(
            UUID recipientId,
            UUID feedbackId,
            long version,
            String title,
            String status,
            boolean handled);

    /**
     * 带版本重载将原始审计或业务操作关联至发件箱；默认方法保持旧插件二进制兼容。 Versioned overload that carries the originating
     * audit/business operation through to the event outbox. The default preserves binary
     * compatibility with already installed plugins.
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
