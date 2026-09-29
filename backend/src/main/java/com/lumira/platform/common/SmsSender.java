package com.lumira.platform.common;

import java.util.UUID;

/** Identity-facing SMS contract; provider credentials stay inside communication. */
public interface SmsSender {
    String verificationMessage(String code);

    boolean enqueue(UUID operationId, String recipient, String text, int lifetimeSeconds);
}
