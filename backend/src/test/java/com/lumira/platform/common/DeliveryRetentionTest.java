package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeliveryRetentionTest {
    @Test
    void purgesBothStoresWithTheSameBoundedPolicy() {
        var mail = mock(TerminalDeliveryQueue.class);
        var sms = mock(TerminalDeliveryQueue.class);
        var job = new DeliveryRetention(List.of(mail, sms), Duration.ofDays(30), 200);

        job.purge();

        verify(mail).purgeTerminal(Duration.ofDays(30), 200);
        verify(sms).purgeTerminal(Duration.ofDays(30), 200);
        verifyNoMoreInteractions(mail, sms);
    }

    @Test
    void rejectsUnsafeRetentionConfiguration() {
        var mail = mock(TerminalDeliveryQueue.class);
        var sms = mock(TerminalDeliveryQueue.class);
        assertThrows(
                IllegalArgumentException.class,
                () -> new DeliveryRetention(List.of(mail, sms), Duration.ZERO, 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DeliveryRetention(List.of(mail, sms), Duration.ofDays(30), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DeliveryRetention(List.of(mail, sms), Duration.ofDays(30), 10_001));
    }
}
