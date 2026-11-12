package com.lumira.plugins.deliverysettings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.*;
import com.lumira.platform.settings.*;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class DeliverySettingWorkflowTest {
    @Test
    void rollbackKeepsTheExplicitCredentialChoiceAndTheHistoricalDataTogether() {
        var store = mock(SettingStore.class);
        var guard = mock(AdministrationPort.class);
        var smtp = mock(SmtpSettingsService.class);
        var mapper = new JsonMapper();
        var data = SmtpSettings.defaults();
        when(store.revision("smtp", 2))
                .thenReturn(
                        Optional.of(
                                new SettingStore.Revision(
                                        2,
                                        mapper.writeValueAsString(data),
                                        "author",
                                        "Publication",
                                        OffsetDateTime.now(),
                                        null)));
        when(smtp.save(eq("admin"), any()))
                .thenReturn(new SmtpSettingsService.View(6, data, true, true));
        var service =
                new DeliverySettingRollbackService(
                        store, mapper, smtp, mock(SmsSettingsService.class), guard);
        var result =
                service.rollback(
                        "smtp",
                        new DeliverySettingRollbackService.Input(
                                5,
                                2,
                                "Restore",
                                new DeliverySettingRollbackService.Credentials("KEEP", null)),
                        "admin");
        assertEquals(6, result.version());
        assertEquals(data, result.data());
        assertTrue(result.passwordConfigured());
        var order = inOrder(guard, store, smtp);
        order.verify(guard).requireOperationGlobal("admin", PlatformOperation.SMTP_UPDATE);
        order.verify(store).revision("smtp", 2);
        order.verify(smtp)
                .save(
                        "admin",
                        new SmtpSettingsService.Input(
                                5,
                                data,
                                new SmtpSettingsService.Credentials("KEEP", null),
                                "Restore",
                                2L));
    }

    @Test
    void rollbackCannotReachAnotherOwnersDataEvenFromAnInternalCaller() {
        var store = mock(SettingStore.class);
        var guard = mock(AdministrationPort.class);
        var smtp = mock(SmtpSettingsService.class);
        var sms = mock(SmsSettingsService.class);
        var service = new DeliverySettingRollbackService(store, new JsonMapper(), smtp, sms, guard);
        for (var section : java.util.List.of("branding", "security", "profile-fields", "passkey")) {
            assertEquals(
                    404,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            service.rollback(
                                                    section,
                                                    new DeliverySettingRollbackService.Input(
                                                            5, 2, "Restore", null),
                                                    "admin"))
                            .getStatusCode()
                            .value());
        }
        verifyNoInteractions(store, guard, smtp, sms);
    }

    @Test
    void concreteHistoryRouteBindsItsVersionWithoutTheHostFallback() throws Exception {
        var history = mock(SettingHistoryReader.class);
        var guard = mock(AdministrationPort.class);
        when(history.detail("sms", 2))
                .thenReturn(
                        new SettingHistoryReader.HistoryDetail(
                                2,
                                java.util.Map.of("sender", "Retained"),
                                "admin",
                                "Publication",
                                OffsetDateTime.now(),
                                null));
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new SmsSettingHistoryController(
                                        history, mock(DeliverySettingRollbackService.class), guard))
                        .build();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/platform/settings/sms/history/2")
                                .principal(
                                        new org.springframework.security.authentication
                                                .UsernamePasswordAuthenticationToken(
                                                "admin", "unused", java.util.List.of())))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.version")
                                .value(2));
        verify(guard).requirePageGlobal("admin", PlatformPageId.SMS);
        verify(history).detail("sms", 2);
    }
}
