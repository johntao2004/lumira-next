package com.lumira.platform.settings;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class SmsSettingsTest {
    @Test void disabledDefaultsAndLoopbackWebhookAreValid() {
        assertDoesNotThrow(() -> SmsSettings.defaults().validate());
        assertDoesNotThrow(() -> new SmsSettings(true, "webhook", "https://sms.example.test/v1/send", "Lumira", "Code {code}", 10).validate());
        assertDoesNotThrow(() -> new SmsSettings(true, "webhook", "http://127.0.0.1:18080/send", "Lumira", "Code {code}", 10).validate());
    }
    @Test void rejectsUnsafeEndpointsAndTemplates() {
        for (String endpoint : new String[]{"http://sms.example.test/send", "https://user:pass@sms.example.test/send", "https://sms.example.test/send?secret=x", "sms.example.test"})
            assertThrows(ResponseStatusException.class, () -> new SmsSettings(true, "webhook", endpoint, "Lumira", "Code {code}", 10).validate());
        assertThrows(ResponseStatusException.class, () -> new SmsSettings(true, "webhook", "", "Lumira", "Code {code}", 10).validate());
        assertThrows(ResponseStatusException.class, () -> new SmsSettings(false, "webhook", "", "Lumira", "Code", 10).validate());
        assertThrows(ResponseStatusException.class, () -> new SmsSettings(true, "other", "https://sms.example.test", "Lumira", "Code {code}", 10).validate());
        assertThrows(ResponseStatusException.class, () -> new SmsSettings(true, "webhook", "https://sms.example.test", "Lumira", "Code {phone}", 10).validate());
    }
    @Test void credentialsAreExplicitAndWriteOnly() {
        for (String action : new String[]{"KEEP", "CLEAR"}) {
            assertDoesNotThrow(() -> new SmsSettingsService.Credentials(action, null).validate());
            assertThrows(ResponseStatusException.class, () -> new SmsSettingsService.Credentials(action, "******").validate());
        }
        var credentials = new SmsSettingsService.Credentials("REPLACE", "fixture-secret"); credentials.validate();
        assertFalse(credentials.toString().contains("fixture-secret"));
        assertFalse(new ObjectMapper().writeValueAsString(credentials).contains("fixture-secret"));
        assertEquals("fixture-secret", new ObjectMapper().readValue("{\"action\":\"REPLACE\",\"value\":\"fixture-secret\"}", SmsSettingsService.Credentials.class).value());
    }
}
