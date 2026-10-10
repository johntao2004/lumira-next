package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.dictionaries.api.DictionaryLookupPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class SecuritySettingsActionsTest {
    @Test
    void removedSecurityHistoryAndRestoreEndpointsDoNotReachStorage() throws Exception {
        var store = mock(SettingStore.class);
        var rollback = mock(SettingRollbackService.class);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new SettingHistoryController(store, rollback, new ObjectMapper()))
                        .build();

        mvc.perform(get("/api/platform/settings/security/history"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/platform/settings/security/history/1"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/platform/settings/security/rollback")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"version\":3,\"sourceVersion\":1,\"changeReason\":\"Restore\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(store, rollback);
    }

    @Test
    void rollbackServiceCannotRestoreSecurityThroughAnInternalCaller() {
        var store = mock(SettingStore.class);
        var service =
                new SettingRollbackService(
                        store,
                        new ObjectMapper(),
                        mock(MaintenanceService.class),
                        mock(PasskeySettingsService.class),
                        mock(SmtpSettingsService.class),
                        mock(SmsSettingsService.class),
                        mock(AdministrationPort.class),
                        mock(DictionaryLookupPort.class));

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.rollback(
                                        "security",
                                        new SettingRollbackService.Input(3, 1, "Restore", null),
                                        "admin"));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(store);
    }
}
