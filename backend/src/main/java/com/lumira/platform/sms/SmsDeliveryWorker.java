package com.lumira.platform.sms;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.communication.DurableDeliveryWorker;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/** SMS adapter; delivery orchestration lives in the communication kernel. */
@Component
public class SmsDeliveryWorker extends DurableDeliveryWorker<OutgoingSms, SmsConnection, SmsGateway.Result> {
    public SmsDeliveryWorker(SmsDeliveryStore store, SmsConnectionSource source, SmsGateway gateway,
                             AuditPort audit, PlatformTransactionManager manager) {
        super(store, source::load, gateway::send, store::complete, SmsGateway.Result::name, store::recoverExpired,
                () -> SmsGateway.Result.DELIVERY_UNCERTAIN, audit, manager, "sms", 0);
    }

    @Scheduled(fixedDelay = 2_000, initialDelay = 10_000)
    public void poll() {
        pollQueue();
    }

    void process(SmsDeliveryStore.Claim claim) {
        processQueue(claim);
    }

    @PreDestroy
    @Override
    public void close() {
        super.close();
    }
}
