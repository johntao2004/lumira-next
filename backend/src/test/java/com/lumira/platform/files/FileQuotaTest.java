package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FileQuotaTest {
    @Test
    void exactByteBoundaryAndCountSlots() {
        var quota = new FileQuota(100, 2);
        assertDoesNotThrow(() -> quota.requireCapacity(90, 1, 10));
        assertThrows(ResponseStatusException.class, () -> quota.requireCapacity(90, 1, 11));
        assertThrows(ResponseStatusException.class, () -> quota.requireCapacity(0, 2, 0));
        assertDoesNotThrow(() -> quota.requireCapacity(100, 1, 0));
    }

    @Test
    void reducedQuotaAndOverflowDoNotAdmitUploads() {
        assertThrows(
                ResponseStatusException.class,
                () -> new FileQuota(100, 2).requireCapacity(101, 0, 0));
        assertThrows(
                ResponseStatusException.class,
                () -> new FileQuota(Long.MAX_VALUE, 2).requireCapacity(Long.MAX_VALUE - 1, 0, 2));
        assertThrows(
                ResponseStatusException.class,
                () -> new FileQuota(100, 0).requireCapacity(0, 0, 0));
    }

    @Test
    void invalidConfigurationAndUsageFail() {
        assertThrows(IllegalArgumentException.class, () -> new FileQuota(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FileQuota(1, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FileQuota(1, 1).requireCapacity(-1, 0, 0));
    }
}
