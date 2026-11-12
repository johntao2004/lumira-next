package com.lumira.platform.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NotificationHrefTest {
    @Test
    void normalizesOptionalLinksAndAcceptsTheLengthLimit() {
        assertNull(NotificationHref.normalize(null));
        assertNull(NotificationHref.normalize("   "));
        assertEquals("/dashboard/home", NotificationHref.normalize("  /dashboard/home  "));
        assertEquals("/" + "a".repeat(511), NotificationHref.normalize("/" + "a".repeat(511)));
        assertEquals(
                "/dashboard/页面?q=中文#section",
                NotificationHref.normalize("/dashboard/页面?q=中文#section"));
        assertEquals(
                "/dashboard/home?q=\\value#\\fragment",
                NotificationHref.normalize("/dashboard/home?q=\\value#\\fragment"));
    }

    @Test
    void rejectsExternalPathsLineBreaksAndOverlongLinks() {
        assertThrows(
                IllegalArgumentException.class, () -> NotificationHref.normalize("//example.com"));
        assertThrows(
                IllegalArgumentException.class,
                () -> NotificationHref.normalize("/dashboard\n/home"));
        assertThrows(
                IllegalArgumentException.class,
                () -> NotificationHref.normalize("/dashboard\r\n/home"));
        assertThrows(IllegalArgumentException.class, () -> NotificationHref.normalize("\t"));
        assertThrows(
                IllegalArgumentException.class,
                () -> NotificationHref.normalize("/\t/outside.example"));
        assertThrows(
                IllegalArgumentException.class,
                () -> NotificationHref.normalize("/\\outside.example"));
        assertThrows(
                IllegalArgumentException.class, () -> NotificationHref.normalize("/folder\\child"));
        assertThrows(
                IllegalArgumentException.class,
                () -> NotificationHref.normalize("/" + "a".repeat(512)));
    }

    @Test
    void onlyHistoricalSafeLinksRemainClickable() {
        assertEquals(
                "/dashboard/home?q=\\value",
                NotificationHref.safeOrNull("/dashboard/home?q=\\value"));
        assertEquals(
                "/dashboard/home#\\value", NotificationHref.safeOrNull("/dashboard/home#\\value"));
        assertNull(NotificationHref.safeOrNull("/\\outside.example"));
        assertNull(NotificationHref.safeOrNull("/dashboard\u0001home"));
    }
}
