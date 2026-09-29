package com.lumira.platform.common;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class PageRequestTest {
    @Test void rejectsInvalidBounds() {
        assertThrows(ResponseStatusException.class, () -> new PageRequest(0, 20, ""));
        assertThrows(ResponseStatusException.class, () -> new PageRequest(1, 0, ""));
        assertThrows(ResponseStatusException.class, () -> new PageRequest(1, 101, ""));
        assertThrows(ResponseStatusException.class, () -> new PageRequest(1, 20, "x".repeat(101)));
    }
    @Test void offsetDoesNotOverflowInteger() {
        assertEquals(214748364600L, new PageRequest(Integer.MAX_VALUE, 100, null).offset());
    }
    @Test void normalizesSearch() {
        assertEquals("admin", new PageRequest(1, 20, " admin ").search());
        assertEquals("", new PageRequest(1, 20, null).search());
    }
}
