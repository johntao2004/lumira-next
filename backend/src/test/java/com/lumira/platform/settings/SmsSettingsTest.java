package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class SmsSettingsTest {
    @Test
    void disabledDefaultsAndLoopbackWebhookAreValid() {
        assertDoesNotThrow(() -> SmsSettings.defaults().validate());
        assertDoesNotThrow(
                () ->
                        new SmsSettings(
                                        true,
                                        "webhook",
                                        "https://sms.example.test/v1/send",
                                        "Lumira",
                                        "Code {code}",
                                        10)
                                .validate());
        assertDoesNotThrow(
                () ->
                        new SmsSettings(
                                        true,
                                        "webhook",
                                        "http://127.0.0.1:18080/send",
                                        "Lumira",
                                        "Code {code}",
                                        10)
                                .validate());
    }

    @Test
    void rejectsUnsafeEndpointsAndTemplates() {
        for (String endpoint :
                new String[] {
                    "http://sms.example.test/send",
                    "https://user:pass@sms.example.test/send",
                    "https://sms.example.test/send?secret=x",
                    "sms.example.test"
                })
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                            new SmsSettings(true, "webhook", endpoint, "Lumira", "Code {code}", 10)
                                    .validate());
        assertThrows(
                ResponseStatusException.class,
                () -> new SmsSettings(true, "webhook", "", "Lumira", "Code {code}", 10).validate());
        assertThrows(
                ResponseStatusException.class,
                () -> new SmsSettings(false, "webhook", "", "Lumira", "Code", 10).validate());
        assertThrows(
                ResponseStatusException.class,
                () ->
                        new SmsSettings(
                                        true,
                                        "other",
                                        "https://sms.example.test",
                                        "Lumira",
                                        "Code {code}",
                                        10)
                                .validate());
        assertThrows(
                ResponseStatusException.class,
                () ->
                        new SmsSettings(
                                        true,
                                        "webhook",
                                        "https://sms.example.test",
                                        "Lumira",
                                        "Code {phone}",
                                        10)
                                .validate());
    }

    @Test
    void rejectsInternalAndReservedWebhookDestinations() {
        for (String host :
                new String[] {
                    "10.0.0.5",
                    "169.254.169.254",
                    "192.168.1.1",
                    "172.16.0.9",
                    "100.64.0.1",
                    "[fd00::1]",
                    "[::ffff:10.0.0.1]",
                    "[64:ff9b::a00:1]",
                    "[2002:a00:1::]",
                    "db.internal",
                    "printer.local",
                    "sms.home",
                    "sms.lan",
                    "singlelabel"
                })
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                            new SmsSettings(
                                            true,
                                            "webhook",
                                            "https://" + host + "/send",
                                            "Lumira",
                                            "Code {code}",
                                            10)
                                    .validate(),
                    host);
        assertDoesNotThrow(
                () ->
                        new SmsSettings(
                                        true,
                                        "webhook",
                                        "https://8.8.8.8/send",
                                        "Lumira",
                                        "Code {code}",
                                        10)
                                .validate());
    }

    @Test
    void credentialsAreExplicitAndWriteOnly() {
        for (String action : new String[] {"KEEP", "CLEAR"}) {
            assertDoesNotThrow(() -> new SmsSettingsService.Credentials(action, null).validate());
            assertThrows(
                    ResponseStatusException.class,
                    () -> new SmsSettingsService.Credentials(action, "******").validate());
        }
        var credentials = new SmsSettingsService.Credentials("REPLACE", "fixture-secret");
        credentials.validate();
        assertFalse(credentials.toString().contains("fixture-secret"));
        assertFalse(new ObjectMapper().writeValueAsString(credentials).contains("fixture-secret"));
        assertEquals(
                "fixture-secret",
                new ObjectMapper()
                        .readValue(
                                "{\"action\":\"REPLACE\",\"value\":\"fixture-secret\"}",
                                SmsSettingsService.Credentials.class)
                        .value());
    }
}
