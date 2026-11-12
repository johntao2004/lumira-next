package com.lumira.platform.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AdmissionCredentialProjectionTest {
    private final Object key = new Object();
    private final Object connection = new Object();
    private final UUID actor = UUID.randomUUID();
    private final AdmissionCredentialProjection.CredentialEpoch epoch =
            new AdmissionCredentialProjection.CredentialEpoch(
                    UUID.randomUUID(), UUID.randomUUID(), true, false);

    @BeforeEach
    void setUp() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        TransactionSynchronizationManager.bindResource(key, connection);
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.unbindResourceIfPossible(key);
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void onlyTheMatchingActorMayConsumeTheProjectionAndOnlyOnce() {
        AdmissionCredentialProjection.within(
                actor,
                epoch,
                () -> {
                    assertTrue(AdmissionCredentialProjection.consume(UUID.randomUUID()).isEmpty());
                    assertEquals(epoch, AdmissionCredentialProjection.consume(actor).orElseThrow());
                    assertTrue(AdmissionCredentialProjection.consume(actor).isEmpty());
                    return null;
                });
        assertTrue(AdmissionCredentialProjection.consume(actor).isEmpty());
    }

    @Test
    void suspendedOrReplacedTransactionCannotConsumeTheParentProjection() {
        AdmissionCredentialProjection.within(
                actor,
                epoch,
                () -> {
                    TransactionSynchronizationManager.unbindResource(key);
                    TransactionSynchronizationManager.bindResource(key, new Object());
                    try {
                        assertTrue(AdmissionCredentialProjection.consume(actor).isEmpty());
                    } finally {
                        TransactionSynchronizationManager.unbindResource(key);
                        TransactionSynchronizationManager.bindResource(key, connection);
                    }
                    assertEquals(epoch, AdmissionCredentialProjection.consume(actor).orElseThrow());
                    return null;
                });
        TransactionSynchronizationManager.setActualTransactionActive(false);
        AdmissionCredentialProjection.within(
                actor,
                epoch,
                () -> {
                    assertTrue(AdmissionCredentialProjection.consume(actor).isEmpty());
                    return null;
                });
    }

    @Test
    void failedNestedResolutionRestoresTheOuterScopeWithoutLeakingEitherProjection() {
        var nestedActor = UUID.randomUUID();
        AdmissionCredentialProjection.within(
                actor,
                epoch,
                () -> {
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    AdmissionCredentialProjection.within(
                                            nestedActor,
                                            epoch,
                                            () -> {
                                                assertEquals(
                                                        epoch,
                                                        AdmissionCredentialProjection.consume(
                                                                        nestedActor)
                                                                .orElseThrow());
                                                throw new IllegalStateException("fixture");
                                            }));
                    assertTrue(AdmissionCredentialProjection.consume(nestedActor).isEmpty());
                    assertEquals(epoch, AdmissionCredentialProjection.consume(actor).orElseThrow());
                    return null;
                });
        assertTrue(AdmissionCredentialProjection.consume(actor).isEmpty());
        assertTrue(AdmissionCredentialProjection.consume(nestedActor).isEmpty());
    }
}
