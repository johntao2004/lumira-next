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
        assertEquals(481026L, AdministrationGuard.lockKey("platform:settings:update"));
        assertEquals(481026L, AdministrationGuard.lockKey("platform:modules:update"));
    }

    @Test
    void sharesAStableBucketWithinADomainAndSeparatesCommonDomains() {
        assertEquals(
                AdministrationGuard.lockKey("platform:users:read"),
                AdministrationGuard.lockKey("platform:users:delete"));
        assertNotEquals(
                AdministrationGuard.lockKey("platform:users:delete"),
                AdministrationGuard.lockKey("platform:files:delete"));
        assertNotEquals(481026L, AdministrationGuard.lockKey("platform:notifications:create"));
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
