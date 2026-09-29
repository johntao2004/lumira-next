package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PasskeySettingsTest {
    @Test
    void defaultsAreDisabledAndValid() {
        var defaults = PasskeySettings.defaults();
        defaults.validate();
        assertTrue(!defaults.enabled());
        assertEquals("127.0.0.1", defaults.rpId());
    }

    @Test
    void normalizedPolicyDeduplicatesOriginsAndRejectsUnsafeOrigin() {
        var policy = new PasskeySettings(true, true, true, " Example.COM ", " Lumira ",
                java.util.List.of("https://example.com", "https://example.com", "https://example.com/"), 120);
        var normalized = policy.normalized();
        assertEquals("example.com", normalized.rpId());
        assertEquals("Lumira", normalized.rpName());
        assertEquals(2, normalized.allowedOrigins().size());
        normalized.validate();

        var unsafe = new PasskeySettings(true, true, true, "example.com", "Lumira",
                java.util.List.of("https://user:secret@example.com/login"), 120);
        assertThrows(ResponseStatusException.class, unsafe::validate);
    }

    @Test
    void disabledPolicyCannotExposePasskeyModes() {
        var invalid = new PasskeySettings(false, true, false, "example.com", "Lumira",
                java.util.List.of("https://example.com"), 120);
        assertThrows(ResponseStatusException.class, invalid::validate);
    }
}
