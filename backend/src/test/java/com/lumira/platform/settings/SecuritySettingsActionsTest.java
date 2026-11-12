package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.PlatformPageId;
import com.lumira.plugins.authenticationconsole.PasskeySettingHistoryController;
import com.lumira.plugins.authenticationconsole.PasskeySettingRollbackService;
import com.lumira.plugins.authenticationconsole.PasskeySettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class SecuritySettingsActionsTest {
    @Test
    void historyReadRequiresOwningGlobalSettingsPageBeforeStorage() {
        var store = mock(SettingStore.class);
        var administration = mock(AdministrationPort.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(administration)
                .requirePageGlobal("reader", PlatformPageId.PASSKEY);
        var controller =
                new PasskeySettingHistoryController(
                        new SettingHistoryReader(store, new ObjectMapper()),
                        mock(PasskeySettingRollbackService.class),
                        administration);
        var actor = mock(Authentication.class);
        when(actor.getName()).thenReturn("reader");

        assertThrows(ResponseStatusException.class, () -> controller.history(1, 10, actor));
        verifyNoInteractions(store);
    }

    @Test
    void removedSecurityHistoryAndRestoreEndpointsDoNotReachStorage() throws Exception {
        var store = mock(SettingStore.class);
        var rollback = mock(PasskeySettingRollbackService.class);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new PasskeySettingHistoryController(
                                        new SettingHistoryReader(store, new ObjectMapper()),
                                        rollback,
                                        mock(AdministrationPort.class)))
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
                new PasskeySettingRollbackService(
                        store,
                        new ObjectMapper(),
                        mock(PasskeySettingsService.class),
                        mock(AdministrationPort.class));

        var error =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.rollback(
                                        "security",
                                        new PasskeySettingRollbackService.Input(3, 1, "Restore"),
                                        "admin"));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(store);
    }
}
