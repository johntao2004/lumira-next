package com.lumira.platform.mail;

import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.communication.DurableDeliveryStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** SMTP-specific adapter over the shared durable-delivery state machine. */
@Repository
public class MailDeliveryStore extends DurableDeliveryStore<OutgoingMail> {
    public MailDeliveryStore(JdbcClient jdbc, SecretKeys keys, ObjectMapper mapper) {
        super(jdbc, keys, mapper, "mail", "platform_mail_delivery", OutgoingMail.class);
    }

    @Transactional(timeout = 5)
    public boolean complete(Claim claim, SmtpGateway.Result result) {
        return completeResult(claim, result == null ? null : result.name());
    }

    @Transactional
    public int recoverExpired() {
        return recoverExpiredEntries().size();
    }
}
