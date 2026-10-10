package com.lumira.platform.sms;

import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.communication.DurableDeliveryStore;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** SMS-specific adapter over the shared durable-delivery state machine. */
@Repository
public class SmsDeliveryStore extends DurableDeliveryStore<OutgoingSms> {
    public SmsDeliveryStore(JdbcClient jdbc, SecretKeys keys, ObjectMapper mapper) {
        super(jdbc, keys, mapper, "sms", "platform_sms_delivery", OutgoingSms.class);
    }

    @Transactional
    public boolean complete(Claim claim, SmsGateway.Result result) {
        return completeResult(claim, result == null ? null : result.name());
    }

    @Transactional
    public List<Recovery> recoverExpired() {
        return recoverExpiredEntries();
    }
}
