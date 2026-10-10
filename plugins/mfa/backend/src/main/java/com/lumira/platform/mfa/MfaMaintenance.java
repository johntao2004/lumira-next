package com.lumira.platform.mfa;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cluster-safe, bounded cleanup for expired enrollment and recovery material. */
@Component
public class MfaMaintenance {
    private final MfaService service;

    public MfaMaintenance(MfaService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${lumira.mfa.cleanup-interval-ms:3600000}",
            initialDelayString = "${lumira.mfa.cleanup-initial-delay-ms:60000}")
    public void purgeExpired() {
        service.purgeExpired(1_000);
    }
}
