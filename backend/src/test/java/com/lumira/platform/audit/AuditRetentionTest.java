package com.lumira.platform.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.*;
import org.junit.jupiter.api.Test;

class AuditRetentionTest {
    @Test
    void purgesOneBoundedBatchAtConfiguredCutoff() {
        var repository = mock(AuditRepository.class);
        var clock = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC);
        var retention = new AuditRetention(repository, Duration.ofDays(30), 250, clock);
        retention.purge();
        verify(repository).purgeBefore(OffsetDateTime.parse("2026-08-15T12:00:00Z"), 250);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsUnsafeRetentionAndBatchBounds() {
        var repository = mock(AuditRepository.class);
        var clock = Clock.systemUTC();
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuditRetention(repository, Duration.ofDays(6), 100, clock));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuditRetention(repository, Duration.ofDays(3651), 100, clock));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuditRetention(repository, Duration.ofDays(30), 0, clock));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuditRetention(repository, Duration.ofDays(30), 5001, clock));
    }

    @Test
    void disabledAuditModuleDoesNotRunBackgroundRetention() {
        var repository = mock(AuditRepository.class);
        var clock = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC);
        var modules = mock(com.lumira.platform.modules.ModuleRuntime.class);
        when(modules.isEnabled("audit-console")).thenReturn(false);
        new AuditRetention(repository, Duration.ofDays(30), 250, clock, modules).purge();
        verifyNoInteractions(repository);
    }
}
