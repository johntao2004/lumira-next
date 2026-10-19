package com.lumira.platform.common;

import java.util.UUID;

/**
 * 面向身份流程的邮件契约；不暴露 SMTP、队列实体或 MVC 类型，便于替换通信实现。 Identity-facing mail contract. The port deliberately
 * contains no SMTP, queue entity, or Spring MVC type so the communication implementation can be
 * replaced without changing identity use cases.
 */
public interface MailSender {
    /** 校验收件地址；无效地址拒绝入队。 Validates the recipient address and rejects invalid recipients. */
    void validateRecipient(String recipient);

    /**
     * 能力未就绪返回状态；持久化和基础设施异常仍向上传播。 Unready capabilities return status; persistence and infrastructure
     * failures still propagate.
     */
    default boolean ready() {
        return false;
    }

    /**
     * 要求邮件能力当前可用，否则抛出不可用错误。 Requires currently available mail capability or raises an unavailable
     * error.
     */
    void ensureAvailable();

    /**
     * 按操作 ID 去重入队并设置秒级有效期；返回是否新增，不表示已投递。 Enqueues by operation ID with lifetime in seconds; returns
     * insertion, not delivery success.
     */
    boolean enqueue(
            UUID operationId, String recipient, String subject, String text, int lifetimeSeconds);
}
