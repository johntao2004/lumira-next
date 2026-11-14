package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NavigationMenuGrantAlignerTest {
    @Test
    void hostPagesUseAccessGrantsAndTheCatalogScope() {
        var print =
                NavigationMenuGrantAligner.hostPages().stream()
                        .filter(page -> "print-templates".equals(page.sectionKey()))
                        .findFirst()
                        .orElseThrow();
        var users =
                NavigationMenuGrantAligner.hostPages().stream()
                        .filter(page -> "users".equals(page.sectionKey()))
                        .findFirst()
                        .orElseThrow();
        var retention =
                NavigationMenuGrantAligner.hostPages().stream()
                        .filter(page -> "file-retention".equals(page.sectionKey()))
                        .findFirst()
                        .orElseThrow();

        assertEquals("platform:print-templates:access", print.permissionKey());
        assertTrue(print.globalOnly());
        assertEquals("platform:users:access", users.permissionKey());
        assertFalse(users.globalOnly());
        assertEquals("platform:file-retention:access", retention.permissionKey());
        assertTrue(retention.globalOnly());
        assertTrue(
                NavigationMenuGrantAligner.needsUpdate(
                        "platform:feedback:read", false, "platform:feedback:access", false));
        assertTrue(
                NavigationMenuGrantAligner.needsUpdate(
                        "platform:print-templates:access",
                        false,
                        "platform:print-templates:access",
                        true));
        assertFalse(
                NavigationMenuGrantAligner.needsUpdate(
                        "platform:users:access", false, "platform:users:access", false));
    }
}
