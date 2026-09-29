package com.lumira.platform.mail;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.communication.DurableDeliveryWorker;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/** SMTP adapter; delivery orchestration lives in the communication kernel. */
@Component
public class MailDeliveryWorker extends DurableDeliveryWorker<OutgoingMail, SmtpConnection, SmtpGateway.Result> {
    public MailDeliveryWorker(MailDeliveryStore store, SmtpConnectionSource source, SmtpGateway gateway,
                              AuditPort audit, PlatformTransactionManager manager) {
        super(store, source::load, gateway::send, store::complete, SmtpGateway.Result::name, store::recoverExpiredEntries,
                () -> SmtpGateway.Result.DELIVERY_UNCERTAIN, audit, manager, "mail", 60_000);
    }

    @Scheduled(fixedDelay = 2_000, initialDelay = 10_000)
    public void poll() {
        pollQueue();
    }

    void process(MailDeliveryStore.Claim claim, long timeoutMillis) {
        processQueue(claim, timeoutMillis);
    }

    @PreDestroy
    @Override
    public void close() {
        super.close();
    }
}
