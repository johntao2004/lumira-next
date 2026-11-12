package com.lumira.platform.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PluginOwnedResourcesTest {
    @Test
    void failedOwnershipRevokesOnlyNewRegistrationAndCloseIsIdempotent() throws Exception {
        var original = new AtomicInteger();
        var rejected = new AtomicInteger();
        var resources = new PluginOwnedResources("fixture", () -> () -> {});
        resources.own("platform-event-consumers", original::incrementAndGet);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        resources.ownRegistration(
                                "platform-event-consumers", rejected::incrementAndGet));
        assertEquals(1, rejected.get());
        assertEquals(0, original.get());
        resources.close();
        resources.close();
        assertEquals(1, original.get());
        assertThrows(
                IllegalArgumentException.class,
                () -> resources.ownRegistration("late", rejected::incrementAndGet));
        assertEquals(2, rejected.get());
    }
}
