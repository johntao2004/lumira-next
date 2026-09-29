package com.lumira.platform.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdministrationGuardTest {
    @Test
    void keepsSchemaChangingBoundariesOnTheHistoricalLock() {
        assertEquals(481026L, AdministrationGuard.lockKey("platform:settings:write"));
        assertEquals(481026L, AdministrationGuard.lockKey("platform:modules:write"));
    }

    @Test
    void sharesAStableBucketWithinADomainAndSeparatesCommonDomains() {
        assertEquals(
                AdministrationGuard.lockKey("platform:users:read"),
                AdministrationGuard.lockKey("platform:users:write"));
        assertNotEquals(
                AdministrationGuard.lockKey("platform:users:write"),
                AdministrationGuard.lockKey("platform:files:write"));
        assertNotEquals(481026L, AdministrationGuard.lockKey("platform:notifications:write"));
    }

    @Test
    void rejectsMissingPermission() {
        assertThrows(IllegalArgumentException.class, () -> AdministrationGuard.lockKey(" "));
        assertThrows(IllegalArgumentException.class, () -> AdministrationGuard.lockKey(null));
    }
}
