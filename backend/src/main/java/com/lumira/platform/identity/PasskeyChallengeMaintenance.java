package com.lumira.platform.identity;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Bounded cleanup keeps consumed/expired challenges from growing forever. */
@Component
public class PasskeyChallengeMaintenance {
    private final PasskeyChallengeStore challenges;

    public PasskeyChallengeMaintenance(PasskeyChallengeStore challenges) {
        this.challenges = challenges;
    }

    @Transactional
    @Scheduled(fixedDelayString = "${lumira.passkey.cleanup-interval-ms:3600000}",
               initialDelayString = "${lumira.passkey.cleanup-initial-delay-ms:60000}")
    public void purge() {
        challenges.purgeExpired(2_000);
    }
}
