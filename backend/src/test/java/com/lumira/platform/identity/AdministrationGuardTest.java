package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    @Test
    void takesTheWriteLockOnlyInsideAWritableTransaction() {
        assertFalse(AdministrationGuard.serializesWrites());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            assertTrue(AdministrationGuard.serializesWrites());
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            assertFalse(AdministrationGuard.serializesWrites());
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
