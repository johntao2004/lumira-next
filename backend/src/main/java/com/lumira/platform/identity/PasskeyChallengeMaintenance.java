package com.lumira.platform.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Bounded cleanup keeps consumed/expired challenges from growing forever. */
@Component
public class PasskeyChallengeMaintenance {
    private static final Logger LOG = LoggerFactory.getLogger(PasskeyChallengeMaintenance.class);
    private static final int BATCH_SIZE = 2_000;
    private static final int MAX_BATCHES = 10;
    private final PasskeyChallengeStore challenges;

    public PasskeyChallengeMaintenance(PasskeyChallengeStore challenges) {
        this.challenges = challenges;
    }

    @Transactional
    @Scheduled(
            fixedDelayString = "${lumira.passkey.cleanup-interval-ms:60000}",
            initialDelayString = "${lumira.passkey.cleanup-initial-delay-ms:60000}")
    public void purge() {
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            if (challenges.purgeExpired(BATCH_SIZE) < BATCH_SIZE) return;
        }
        LOG.warn(
                "Passkey challenge cleanup reached its {}-row budget; expired backlog may remain",
                BATCH_SIZE * MAX_BATCHES);
    }
}
