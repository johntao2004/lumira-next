package com.lumira.platform.identity;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReauthenticationMaintenance {
    private final ReauthenticationService service;

    public ReauthenticationMaintenance(ReauthenticationService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${lumira.reauthentication.cleanup-interval-ms:3600000}",
            initialDelayString = "${lumira.reauthentication.cleanup-initial-delay-ms:60000}")
    @Transactional
    public void purgeExpired() {
        service.purgeExpired(2_000);
    }
}
